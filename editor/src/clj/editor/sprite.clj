;; Copyright 2020-2023 The Defold Foundation
;; Copyright 2014-2020 King
;; Copyright 2009-2014 Ragnar Svensson, Christian Murray
;; Licensed under the Defold License version 1.0 (the "License"); you may not use
;; this file except in compliance with the License.
;; 
;; You may obtain a copy of the License, together with FAQs at
;; https://www.defold.com/license
;; 
;; Unless required by applicable law or agreed to in writing, software distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns editor.sprite
  (:require [dynamo.graph :as g]
            [editor.colors :as colors]
            [editor.defold-project :as project]
            [editor.geom :as geom]
            [editor.gl :as gl]
            [editor.gl.pass :as pass]
            [editor.gl.shader :as shader]
            [editor.gl.texture :as texture]
            [editor.gl.vertex2 :as vtx]
            [editor.graph-util :as gu]
            [editor.graphics :as graphics]
            [editor.material :as material]
            [editor.pipeline :as pipeline]
            [editor.properties :as properties]
            [editor.resource :as resource]
            [editor.resource-node :as resource-node]
            [editor.scene-picking :as scene-picking]
            [editor.slice9 :as slice9]
            [editor.texture-set :as texture-set]
            [editor.types :as types]
            [editor.validation :as validation]
            [editor.workspace :as workspace])
  (:import [com.dynamo.bob.pipeline ShaderUtil$Common ShaderUtil$VariantTextureArrayFallback]
           [com.dynamo.gamesys.proto Sprite$SpriteDesc Sprite$SpriteDesc$BlendMode Sprite$SpriteDesc$SizeMode]
           [com.jogamp.opengl GL GL2]
           [editor.gl.shader ShaderLifecycle]
           [editor.gl.vertex2 VertexBuffer]
           [editor.types AABB]
           [java.nio ByteBuffer]
           [javax.vecmath Matrix4d Point3d]))

(set! *warn-on-reflection* true)

(def sprite-icon "icons/32/Icons_14-Sprite.png")

(defn- v3->v4 [v]
  (conj v 0.0))

(defn- v4->v3 [v4]
  (subvec v4 0 3))

; Render assets
(vtx/defvertex texture-vtx
  (vec4 position)
  (vec2 texcoord0)
  (vec1 page_index))

(vtx/defvertex color-vtx
  (vec3 position)
  (vec4 color))

(shader/defshader outline-vertex-shader
  (attribute vec4 position)
  (attribute vec4 color)
  (varying vec4 var_color)
  (defn void main []
    (setq gl_Position (* gl_ModelViewProjectionMatrix position))
    (setq var_color color)))

(shader/defshader outline-fragment-shader
  (varying vec4 var_color)
  (defn void main []
    (setq gl_FragColor var_color)))

; TODO - macro of this
(def outline-shader (shader/make-shader ::outline-shader outline-vertex-shader outline-fragment-shader))

(defn- renderable-data [renderable]
  (let [{:keys [world-transform updatable user-data]} renderable
        {:keys [animation size-mode size slice9 vertex-attribute-bytes]} user-data
        frame-index (get-in updatable [:state :frame] 0)
        animation-frame (get-in animation [:frames frame-index])
        page-index (:page-index animation-frame)
        vertex-data (if (= :size-mode-auto size-mode)
                      (texture-set/vertex-data animation-frame)
                      (slice9/vertex-data animation-frame size slice9 :pivot-center))]
    (assoc vertex-data
      :page-index page-index
      :world-transform world-transform
      :vertex-attribute-bytes vertex-attribute-bytes)))

(defn- into-vertex-buffer [^VertexBuffer vbuf renderables]
  (let [renderable-datas (mapv renderable-data renderables)
        vertex-description (.vertex-description vbuf)
        vertex-byte-stride (:size vertex-description)
        ^ByteBuffer buf (.buf vbuf)

        put-bytes!
        (fn put-bytes!
          ^long [^long vertex-byte-offset vertices]
          (reduce (fn [^long vertex-byte-offset attribute-bytes]
                    (vtx/buf-blit! buf vertex-byte-offset attribute-bytes)
                    (+ vertex-byte-offset vertex-byte-stride))
                  vertex-byte-offset
                  vertices))

        put-numbers!
        (fn put-numbers!
          ^long [^long vertex-byte-offset vtx-attribute-type normalize vertices]
          (reduce (fn [^long vertex-byte-offset attribute-numbers]
                    (vtx/buf-put! buf vertex-byte-offset vtx-attribute-type normalize attribute-numbers)
                    (+ vertex-byte-offset vertex-byte-stride))
                  vertex-byte-offset
                  vertices))

        put-renderables!
        (fn put-renderables!
          ^long [^long attribute-byte-offset renderable-data->vertices put-vertices!]
          (reduce (fn [^long vertex-byte-offset renderable-data]
                    (let [vertices (renderable-data->vertices renderable-data)]
                      (put-vertices! vertex-byte-offset vertices)))
                  attribute-byte-offset
                  renderable-datas))]

    (reduce (fn [^long attribute-byte-offset attribute]
              (let [vtx-attribute-type (:type attribute)
                    normalize (:normalize attribute)
                    name-key (:name-key attribute)

                    put-attribute-numbers!
                    (fn put-attribute-numbers!
                      ^long [^long vertex-byte-offset vertices]
                      (put-numbers! vertex-byte-offset vtx-attribute-type normalize vertices))]

                (case (:semantic-type attribute)
                  :semantic-type-position-local
                  (put-renderables! attribute-byte-offset :position-data put-attribute-numbers!)

                  :semantic-type-position-world
                  (put-renderables! attribute-byte-offset
                                    (fn [renderable-data]
                                      (let [local-positions (:position-data renderable-data)
                                            world-transform (:world-transform renderable-data)]
                                        (geom/transf-p4 world-transform local-positions)))
                                    put-attribute-numbers!)

                  :semantic-type-texcoord
                  (put-renderables! attribute-byte-offset :uv-data put-attribute-numbers!)

                  :semantic-type-page-index
                  (put-renderables! attribute-byte-offset
                                    (fn [renderable-data]
                                      (let [vertex-count (count (:position-data renderable-data))
                                            page-index (:page-index renderable-data)]
                                        (repeat vertex-count [(double page-index)])))
                                    put-attribute-numbers!)

                  ;; Default case.
                  (put-renderables! attribute-byte-offset
                                    (fn [renderable-data]
                                      (let [vertex-count (count (:position-data renderable-data))
                                            attribute-bytes (get (:vertex-attribute-bytes renderable-data) name-key)]
                                        (repeat vertex-count attribute-bytes)))
                                    put-bytes!))

                (+ attribute-byte-offset
                   (vtx/attribute-size attribute))))
            0
            (:attributes vertex-description))
    (.position buf (.limit buf))
    (vtx/flip! vbuf)))

(defn- gen-outline-vertex [^Matrix4d wt ^Point3d pt x y cr cg cb]
  (.set pt x y 0)
  (.transform wt pt)
  [(.x pt) (.y pt) (.z pt) cr cg cb 1])

(defn- conj-outline-quad! [^ByteBuffer buf ^Matrix4d wt ^Point3d pt width height cr cg cb]
  (let [x1 (* 0.5 width)
        y1 (* 0.5 height)
        x0 (- x1)
        y0 (- y1)
        v0 (gen-outline-vertex wt pt x0 y0 cr cg cb)
        v1 (gen-outline-vertex wt pt x1 y0 cr cg cb)
        v2 (gen-outline-vertex wt pt x1 y1 cr cg cb)
        v3 (gen-outline-vertex wt pt x0 y1 cr cg cb)]
    (doto buf
      (vtx/buf-push! v0)
      (vtx/buf-push! v1)
      (vtx/buf-push! v1)
      (vtx/buf-push! v2)
      (vtx/buf-push! v2)
      (vtx/buf-push! v3)
      (vtx/buf-push! v3)
      (vtx/buf-push! v0))))

(defn- conj-outline-slice9-quad! [buf line-data ^Matrix4d world-transform tmp-point cr cg cb]
  (transduce (map (fn [[x y]]
                    (gen-outline-vertex world-transform tmp-point x y cr cg cb)))
             vtx/buf-push!
             buf
             line-data))

(defn- gen-outline-vertex-buffer [renderables count]
  (let [tmp-point (Point3d.)
        ^VertexBuffer vbuf (->color-vtx (* count 8))
        ^ByteBuffer buf (.buf vbuf)]
    (doseq [renderable renderables]
      (let [[cr cg cb] (if (:selected renderable) colors/selected-outline-color colors/outline-color)
            world-transform (:world-transform renderable)
            {:keys [animation size size-mode slice9]} (:user-data renderable)
            [quad-width quad-height] size
            animation-frame-index (or (some-> renderable :updatable :state :frame) 0)
            animation-frame (get-in animation [:frames animation-frame-index])]
        (if (= :size-mode-auto size-mode)
          (conj-outline-quad! buf world-transform tmp-point quad-width quad-height cr cg cb)
          (let [slice9-data (slice9/vertex-data animation-frame size slice9 :pivot-center)
                line-data (:line-data slice9-data)]
            (conj-outline-slice9-quad! buf line-data world-transform tmp-point cr cg cb)))))
    (vtx/flip! vbuf)))

; Rendering

(shader/defshader sprite-id-vertex-shader
  (uniform mat4 view_proj)
  (attribute vec4 position)
  (attribute vec2 texcoord0)
  (attribute float page_index)
  (varying vec2 var_texcoord0)
  (varying float var_page_index)
  (defn void main []
    (setq gl_Position (* view_proj (vec4 position.xyz 1.0)))
    (setq var_texcoord0 texcoord0)
    (setq var_page_index page_index)))

(shader/defshader sprite-id-fragment-shader
  (varying vec2 var_texcoord0)
  (varying float var_page_index)
  (uniform vec4 id)
  (uniform sampler2DArray texture_sampler)
  (defn void main []
  (setq vec4 color (texture2DArray texture_sampler (vec3 var_texcoord0 var_page_index)))
  (if (> color.a 0.05)
    (setq gl_FragColor id)
    (discard))))


(def id-shader
  (let [augmented-fragment-shader-source (.source (ShaderUtil$VariantTextureArrayFallback/transform sprite-id-fragment-shader ShaderUtil$Common/MAX_ARRAY_SAMPLERS))]
    (shader/make-shader ::sprite-id-shader sprite-id-vertex-shader augmented-fragment-shader-source {"view_proj" :view-proj "id" :id})))

(defn- quad-count [size-mode slice9]
  (let [[^double x0 ^double y0 ^double x1 ^double y1] slice9
        columns (cond-> 1 (pos? x0) inc (pos? x1) inc)
        rows (cond-> 1 (pos? y0) inc (pos? y1) inc)]
    (if (= :size-mode-auto size-mode)
      1
      (* columns rows))))

(defn- count-quads [renderables]
  (transduce (map (comp :quad-count :user-data))
             +
             0
             renderables))

(declare ^:private get-attribute-values)

(defn- get-attribute-values-from-node [material-attribute vertex-attribute-overrides]
  (or (vertex-attribute-overrides (:name-key material-attribute))
      (:values material-attribute)))

(defn render-sprites [^GL2 gl render-args renderables _count]
  (let [user-data (:user-data (first renderables))
        gpu-texture (:gpu-texture user-data)
        pass (:pass render-args)
        num-quads (count-quads renderables)]
    ;; Maybe do something with the vertex values here?
    (condp = pass
      pass/transparent
      (let [shader (:shader user-data)
            shader-bound-attribute? (comp boolean (shader/get-attribute-infos shader gl) :name)
            material-attribute-infos (:material-attribute-infos user-data)
            declared-material-attribute-keyword? (into #{} (map :name-key) material-attribute-infos)
            manufactured-attributes (cond-> []

                                            (not (declared-material-attribute-keyword? :position))
                                            (conj {:name "position"
                                                   :name-key :position
                                                   :semantic-type :semantic-type-position-world
                                                   :data-type :type-float
                                                   :element-count 4})

                                            (not (declared-material-attribute-keyword? :texcoord0))
                                            (conj {:name "texcoord0"
                                                   :name-key :texcoord0
                                                   :semantic-type :semantic-type-texcoord
                                                   :data-type :type-float
                                                   :element-count 2})

                                            (not (declared-material-attribute-keyword? :page-index))
                                            (conj {:name "page_index"
                                                   :name-key :page-index
                                                   :semantic-type :semantic-type-page-index
                                                   :data-type :type-float
                                                   :element-count 1}))
            all-attributes (into manufactured-attributes material-attribute-infos)
            shader-bound-attributes (filterv shader-bound-attribute? all-attributes)
            vertex-description (graphics/make-vertex-description shader-bound-attributes)
            vbuf (into-vertex-buffer (vtx/make-vertex-buffer vertex-description :dynamic (* num-quads 6)) renderables)
            vertex-binding (vtx/use-with ::sprite-trans vbuf shader)
            blend-mode (:blend-mode user-data)]
        (gl/with-gl-bindings gl render-args [shader vertex-binding gpu-texture]
          (shader/set-samplers-by-index shader gl 0 (:texture-units gpu-texture))
          (gl/set-blend-mode gl blend-mode)
          (gl/gl-draw-arrays gl GL/GL_TRIANGLES 0 (* num-quads 6))
          (.glBlendFunc gl GL/GL_SRC_ALPHA GL/GL_ONE_MINUS_SRC_ALPHA)))

      pass/selection
      (let [vbuf (into-vertex-buffer (->texture-vtx (* num-quads 6)) renderables)
            vertex-binding (vtx/use-with ::sprite-selection vbuf id-shader)]
        (gl/with-gl-bindings gl (assoc render-args :id (scene-picking/renderable-picking-id-uniform (first renderables))) [id-shader vertex-binding gpu-texture]
          (shader/set-samplers-by-index id-shader gl 0 (:texture-units gpu-texture))
          (gl/gl-draw-arrays gl GL/GL_TRIANGLES 0 (* num-quads 6)))))))

(defn- render-sprite-outlines [^GL2 gl render-args renderables count]
  (assert (= pass/outline (:pass render-args)))
  (let [num-quads (count-quads renderables)
        outline-vertex-binding (vtx/use-with ::sprite-outline (gen-outline-vertex-buffer renderables num-quads) outline-shader)]
    (gl/with-gl-bindings gl render-args [outline-shader outline-vertex-binding]
      (gl/gl-draw-arrays gl GL/GL_LINES 0 (* num-quads 8)))))

(defn- pb-overridden-attributes [material-attribute-infos vertex-attribute-overrides]
  (let [overridden-material-attribute-infos (filter #(contains? vertex-attribute-overrides (:name-key %))
                                                    material-attribute-infos)
        pb-vertex-attributes (mapv (fn [material-attribute-info]
                                     (let [overridden-value ((:name-key material-attribute-info) vertex-attribute-overrides)
                                           value-keyword (graphics/attribute-data-type->attribute-value-keyword (:data-type material-attribute-info))]
                                       (-> material-attribute-info
                                           (assoc value-keyword {:v overridden-value})
                                           (dissoc :name-hash :bytes :name-key :values))))
                                   overridden-material-attribute-infos)]
    pb-vertex-attributes))

; Node defs

(g/defnk produce-save-value [image default-animation material material-attribute-infos blend-mode size-mode manual-size slice9 offset playback-rate vertex-attribute-overrides]
  (cond-> {:tile-set (resource/resource->proj-path image)
           :default-animation default-animation
           :material (resource/resource->proj-path material)
           :blend-mode blend-mode
           :attributes (pb-overridden-attributes material-attribute-infos vertex-attribute-overrides)}

          (not= [0.0 0.0 0.0 0.0] slice9)
          (assoc :slice9 slice9)

          (not= 0.0 offset)
          (assoc :offset offset)

          (not= 1.0 playback-rate)
          (assoc :playback-rate playback-rate)

          (not= :size-mode-auto size-mode)
          (cond-> :always
                  (assoc :size-mode size-mode)

                  (not= [0.0 0.0 0.0] manual-size)
                  (assoc :size (v3->v4 manual-size)))))

(g/defnk produce-scene
  [_node-id aabb gpu-texture material-shader animation blend-mode size-mode size slice9 material-attribute-infos vertex-attribute-bytes]
  (cond-> {:node-id _node-id
           :aabb aabb
           :renderable {:passes [pass/selection]}}
    (seq (:frames animation))
    (assoc :renderable {:render-fn render-sprites
                        :batch-key [gpu-texture blend-mode material-shader]
                        :select-batch-key _node-id
                        :tags #{:sprite}
                        :user-data {:gpu-texture gpu-texture
                                    :shader material-shader
                                    :material-attribute-infos material-attribute-infos
                                    :vertex-attribute-bytes vertex-attribute-bytes
                                    :animation animation
                                    :blend-mode blend-mode
                                    :size-mode size-mode
                                    :size size
                                    :slice9 slice9
                                    :quad-count (quad-count size-mode slice9)}
                        :passes [pass/transparent pass/selection]})

    (and (:width animation) (:height animation))
    (assoc :children [{:node-id _node-id
                       :aabb aabb
                       :renderable {:render-fn render-sprite-outlines
                                    :batch-key [outline-shader]
                                    :tags #{:sprite :outline}
                                    :select-batch-key _node-id
                                    :user-data {:animation animation
                                                :size-mode size-mode
                                                :size size
                                                :slice9 slice9
                                                :quad-count (quad-count size-mode slice9)}
                                    :passes [pass/outline]}}])

    (< 1 (count (:frames animation)))
    (assoc :updatable (texture-set/make-animation-updatable _node-id "Sprite" animation))))

(defn- page-count-mismatch-error-message [is-paged-material texture-page-count material-max-page-count]
  (when (and (some? texture-page-count)
             (some? material-max-page-count))
    (cond
      (and is-paged-material
           (zero? texture-page-count))
      "The Material expects a paged Atlas, but the selected Image is not paged"

      (and (not is-paged-material)
           (pos? texture-page-count))
      "The Material does not support paged Atlases, but the selected Image is paged"

      (< material-max-page-count texture-page-count)
      "The Material's 'Max Page Count' is not sufficient for the number of pages in the selected Image")))

(defn- validate-material [_node-id material material-max-page-count material-shader texture-page-count]
  (let [is-paged-material (shader/is-using-array-samplers? material-shader)]
    (or (validation/prop-error :fatal _node-id :material validation/prop-nil? material "Material")
        (validation/prop-error :fatal _node-id :material validation/prop-resource-not-exists? material "Material")
        (validation/prop-error :fatal _node-id :material page-count-mismatch-error-message is-paged-material texture-page-count material-max-page-count))))

(g/defnk produce-build-targets [_node-id resource image anim-ids default-animation material material-attribute-infos material-max-page-count material-shader blend-mode size-mode manual-size slice9 texture-page-count dep-build-targets offset playback-rate vertex-attribute-overrides]
  (or (when-let [errors (->> [(validation/prop-error :fatal _node-id :image validation/prop-nil? image "Image")
                              (validate-material _node-id material material-max-page-count material-shader texture-page-count)
                              (validation/prop-error :fatal _node-id :default-animation validation/prop-nil? default-animation "Default Animation")
                              (validation/prop-error :fatal _node-id :default-animation validation/prop-anim-missing? default-animation anim-ids)]
                             (remove nil?)
                             (seq))]
        (g/error-aggregate errors))
      [(pipeline/make-protobuf-build-target resource dep-build-targets
                                            Sprite$SpriteDesc
                                            {:tile-set image
                                             :default-animation default-animation
                                             :material material
                                             :blend-mode blend-mode
                                             :size-mode size-mode
                                             :size (v3->v4 manual-size)
                                             :slice9 slice9
                                             :offset offset
                                             :playback-rate playback-rate
                                             :attributes (graphics/attributes->build-target (pb-overridden-attributes material-attribute-infos vertex-attribute-overrides))}
                                            [:tile-set :material])]))
(defn- fill-with-zeros [values expected-count]
  (let [trailing-vec-with-zeros (repeat (- expected-count (count values)) 0)]
    (vec (concat values trailing-vec-with-zeros))))

(defn- get-attribute-property-type [attribute]
  (let [attribute-element-count (:element-count attribute)
        attribute-semantic-type (:semantic-type attribute)]
    (cond (= attribute-semantic-type :semantic-type-color) types/Color
          (= attribute-element-count 1) g/Num
          (= attribute-element-count 2) types/Vec2
          (= attribute-element-count 3) types/Vec3
          (= attribute-element-count 4) types/Vec4)))

(defn- get-attribute-expected-element-count [attribute]
  (let [attribute-element-count (:element-count attribute)
        attribute-semantic-type (:semantic-type attribute)]
    (if (= attribute-semantic-type :semantic-type-color)
      4
      attribute-element-count)))

(defn- attribute-update-property [current-property-value attribute new-value]
  (let [attribute-key (:name-key attribute)
        attribute-tbl-updated (assoc current-property-value attribute-key new-value)]
    attribute-tbl-updated))

(defn- get-attribute-edit-type [attribute prop-type]
  (let [attribute-semantic-type (:semantic-type attribute)
        attribute-update-fn (fn [_evaluation-context self _old-value new-value]
                              (g/update-property self :vertex-attribute-overrides attribute-update-property attribute new-value))]
    (if (= attribute-semantic-type :semantic-type-color)
      {:type types/Color
       :ignore-alpha? false
       :set-fn attribute-update-fn}
      {:type prop-type
       :set-fn attribute-update-fn})))

(defn- attributes->intermediate-backing [attributes]
  (into {}
        (map (fn [attribute]
               [(vtx/attribute-name->key (:name attribute))
                (graphics/attribute->values attribute)]))
        attributes))

(g/defnk produce-properties [_node-id _declared-properties material-attribute-infos vertex-attribute-overrides]
  (let [attribute-property-keys (map :name-key material-attribute-infos)
        attribute-properties (map (fn [attribute]
                                    (let [attribute-values (get-attribute-values-from-node attribute vertex-attribute-overrides)
                                          attribute-type (get-attribute-property-type attribute)
                                          attribute-expected-value-count (get-attribute-expected-element-count attribute)
                                          attribute-edit-type (get-attribute-edit-type attribute attribute-type)
                                          attribute-key (:name-key attribute)
                                          attribute-prop {:node-id _node-id
                                                          :type attribute-type
                                                          :edit-type attribute-edit-type
                                                          :value (fill-with-zeros attribute-values attribute-expected-value-count)
                                                          :label (properties/keyword->name attribute-key)}]
                                      {attribute-key attribute-prop}))
                                  material-attribute-infos)]
    (-> _declared-properties
        (update :properties into attribute-properties)
        (update :display-order into attribute-property-keys))))

(g/defnk produce-vertex-attribute-bytes [material-attribute-infos vertex-attribute-overrides]
  (into {}
        (map (fn [material-attribute-info]
               (let [name-key (:name-key material-attribute-info)
                     bytes (if-some [override-values (get vertex-attribute-overrides name-key)]
                             (.array (graphics/make-attribute-byte-buffer (:data-type material-attribute-info) override-values))
                             (:bytes material-attribute-info))]
                 [name-key bytes])))
        material-attribute-infos))

(g/defnode SpriteNode
  (inherits resource-node/ResourceNode)

  (property image resource/Resource
            (value (gu/passthrough image-resource))
            (set (fn [evaluation-context self old-value new-value]
                   (project/resource-setter evaluation-context self old-value new-value
                                            [:resource :image-resource]
                                            [:anim-data :anim-data]
                                            [:anim-ids :anim-ids]
                                            [:gpu-texture :gpu-texture]
                                            [:texture-page-count :texture-page-count]
                                            [:build-targets :dep-build-targets])))
            (dynamic error (g/fnk [_node-id image anim-data]
                                  (or (validation/prop-error :info _node-id :image validation/prop-nil? image "Image")
                                      (validation/prop-error :fatal _node-id :image validation/prop-resource-not-exists? image "Image")
                                      (when (nil? anim-data) ; nil from :substitute on input.
                                        (g/->error _node-id :image :fatal image "the assigned Image has internal errors")))))
            (dynamic edit-type (g/constantly
                                 {:type resource/Resource
                                  :ext ["atlas" "tilesource"]})))

  (property default-animation g/Str
            (dynamic error (g/fnk [_node-id image anim-ids default-animation]
                                  (when image
                                    (or (validation/prop-error :fatal _node-id :default-animation validation/prop-empty? default-animation "Default Animation")
                                        (validation/prop-error :fatal _node-id :default-animation validation/prop-anim-missing? default-animation anim-ids)))))
            (dynamic edit-type (g/fnk [anim-ids] (properties/->choicebox anim-ids))))

  (property material resource/Resource
            (value (gu/passthrough material-resource))
            (set (fn [evaluation-context self old-value new-value]
                   (project/resource-setter evaluation-context self old-value new-value
                                            [:resource :material-resource]
                                            [:shader :material-shader]
                                            [:samplers :material-samplers]
                                            [:max-page-count :material-max-page-count]
                                            [:attribute-infos :material-attribute-infos]
                                            [:build-targets :dep-build-targets])))
            (dynamic edit-type (g/constantly {:type resource/Resource :ext #{"material"}}))
            (dynamic error (g/fnk [_node-id material material-max-page-count material-shader texture-page-count]
                             (validate-material _node-id material material-max-page-count material-shader texture-page-count))))

  (property blend-mode g/Any (default :blend-mode-alpha)
            (dynamic tip (validation/blend-mode-tip blend-mode Sprite$SpriteDesc$BlendMode))
            (dynamic edit-type (g/constantly (properties/->pb-choicebox Sprite$SpriteDesc$BlendMode))))
  (property size-mode g/Keyword (default :size-mode-auto)
            (set (fn [evaluation-context self old-value new-value]
                   ;; Use the texture size for the :manual-size when the user switches
                   ;; from :size-mode-auto to :size-mode-manual.
                   (when (and (= :size-mode-auto old-value)
                              (= :size-mode-manual new-value)
                              (properties/user-edit? self :size-mode evaluation-context))
                     (when-some [animation (g/node-value self :animation evaluation-context)]
                       (let [texture-size [(double (:width animation)) (double (:height animation)) 0.0]]
                         (g/set-property self :manual-size texture-size))))))
            (dynamic edit-type (g/constantly (properties/->pb-choicebox Sprite$SpriteDesc$SizeMode))))
  (property manual-size types/Vec3 (default [0.0 0.0 0.0])
            (dynamic visible (g/constantly false)))
  (property size types/Vec3
            (value (g/fnk [manual-size size-mode animation]
                     (if (and (some? animation)
                              (or (= :size-mode-auto size-mode)
                                  (= [0.0 0.0 0.0] manual-size)))
                       [(double (:width animation)) (double (:height animation)) 0.0]
                       manual-size)))
            (set (fn [_evaluation-context self _old-value new-value]
                   (g/set-property self :manual-size new-value)))
            (dynamic read-only? (g/fnk [size-mode] (= :size-mode-auto size-mode))))
  (property slice9 types/Vec4 (default [0.0 0.0 0.0 0.0])
            (dynamic read-only? (g/fnk [size-mode] (= :size-mode-auto size-mode)))
            (dynamic edit-type (g/constantly {:type types/Vec4 :labels ["L" "T" "R" "B"]})))
  (property playback-rate g/Num (default 1.0))
  (property offset g/Num (default 0.0)
            (dynamic edit-type (g/constantly {:type :slider
                                              :min 0.0
                                              :max 1.0
                                              :precision 0.01})))
  (property vertex-attribute-overrides g/Any
            (default {})
            (dynamic visible (g/constantly false)))

  (input image-resource resource/Resource)
  (input anim-data g/Any :substitute nil)
  (input anim-ids g/Any)
  (input gpu-texture g/Any)
  (input texture-page-count g/Int :substitute nil)
  (input dep-build-targets g/Any :array)

  (input material-resource resource/Resource)
  (input material-shader ShaderLifecycle)
  (input material-samplers g/Any)
  (input material-max-page-count g/Int)
  (input material-attribute-infos g/Any)
  (input default-tex-params g/Any)

  (output tex-params g/Any (g/fnk [material-samplers default-tex-params]
                             (or (some-> material-samplers first material/sampler->tex-params)
                                 default-tex-params)))
  (output gpu-texture g/Any (g/fnk [gpu-texture tex-params] (texture/set-params gpu-texture tex-params)))
  (output animation g/Any (g/fnk [anim-data default-animation] (get anim-data default-animation))) ; TODO - use placeholder animation
  (output aabb AABB (g/fnk [size]
                      (let [[^double width ^double height ^double depth] size
                            half-width (* 0.5 width)
                            half-height (* 0.5 height)
                            half-depth (* 0.5 depth)]
                        (geom/make-aabb (Point3d. (- half-width) (- half-height) (- half-depth))
                                        (Point3d. half-width half-height half-depth)))))
  (output save-value g/Any produce-save-value)
  (output scene g/Any :cached produce-scene)
  (output build-targets g/Any :cached produce-build-targets)
  (output _properties g/Properties :cached produce-properties)
  (output vertex-attribute-bytes g/Any :cached produce-vertex-attribute-bytes))

(defn load-sprite [project self resource sprite]
  (let [image (workspace/resolve-resource resource (:tile-set sprite))
        material (workspace/resolve-resource resource (:material sprite))]
    (concat
      (g/connect project :default-tex-params self :default-tex-params)
      (g/set-property self :default-animation (:default-animation sprite))
      (g/set-property self :material material)
      (g/set-property self :blend-mode (:blend-mode sprite))
      (g/set-property self :size-mode (:size-mode sprite))
      (g/set-property self :manual-size (v4->v3 (:size sprite)))
      (g/set-property self :slice9 (:slice9 sprite))
      (g/set-property self :image image)
      (g/set-property self :offset (:offset sprite))
      (g/set-property self :playback-rate (:playback-rate sprite))
      (g/set-property self :vertex-attribute-overrides (attributes->intermediate-backing (:attributes sprite))))))

(defn register-resource-types [workspace]
  (resource-node/register-ddf-resource-type workspace
    :ext "sprite"
    :node-type SpriteNode
    :ddf-type Sprite$SpriteDesc
    :load-fn load-sprite
    :icon sprite-icon
    :view-types [:scene :text]
    :tags #{:component}
    :tag-opts {:component {:transform-properties #{:position :rotation :scale}}}
    :label "Sprite"))

;; TODO(vertex-attr):
;; * Edit the values in the material view
;; * Verify editor protobuf to map conversion handles OneOf fields correctly (add tests?).
;; * Rename `VertexAttribute.byte_values` to `binary_values` to reflect that it is a write-only field for the engine runtime.
;; * local vs world-space as flag rather than embedded in semantic type?
;; * Make everything optional except name `VertexAttribute`.
;; * Strip everything but name & values from attributes in .sprite files.
