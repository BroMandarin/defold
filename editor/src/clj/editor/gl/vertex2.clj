;; Copyright 2020-2022 The Defold Foundation
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

(ns editor.gl.vertex2
  (:require
   [clojure.string :as str]
   [editor.gl :as gl]
   [editor.gl.protocols :refer [GlBind]]
   [editor.gl.shader :as shader]
   [editor.scene-cache :as scene-cache]
   [internal.util :as util])
  (:import
   [com.jogamp.common.nio Buffers]
   [java.nio Buffer ByteBuffer ByteOrder DoubleBuffer FloatBuffer IntBuffer LongBuffer ShortBuffer]
   [com.jogamp.opengl GL GL2]))

(set! *warn-on-reflection* true)

(def type-sizes
  {:ubyte  Buffers/SIZEOF_BYTE
   :byte   Buffers/SIZEOF_BYTE
   :ushort Buffers/SIZEOF_SHORT
   :short  Buffers/SIZEOF_SHORT
   :uint   Buffers/SIZEOF_INT
   :int    Buffers/SIZEOF_INT
   :float  Buffers/SIZEOF_FLOAT
   :double Buffers/SIZEOF_DOUBLE})

(def gl-types
  {:ubyte   GL/GL_UNSIGNED_BYTE
   :byte    GL/GL_BYTE
   :ushort  GL/GL_UNSIGNED_SHORT
   :short   GL/GL_SHORT
   :uint    GL/GL_UNSIGNED_INT
   :int     GL2/GL_INT
   :float   GL/GL_FLOAT
   :double  GL2/GL_DOUBLE})

(def usage-types
  {:static GL2/GL_STATIC_DRAW
   :dynamic GL2/GL_DYNAMIC_DRAW})

(defn type->stream-type [type]
  (case type
    :float :value-type-float32
    :ubyte :value-type-uint8
    :ushort :value-type-uint16
    :uint :value-type-uint32
    :byte :value-type-int8
    :short :value-type-int16
    :int :value-type-int32))

;; TODO: Might need to add support for uint64/int64 in the future.
;;       (OpenGL ES 2 doesn't support this currently, but Vulkan might.)
(defn stream-type->type [stream-type]
  (case stream-type
    :value-type-float32 :float
    :value-type-uint8 :ubyte
    :value-type-uint16 :ushort
    :value-type-uint32 :uint
    :value-type-int8 :byte
    :value-type-int16 :short
    :value-type-int32 :int))

;; VertexBuffer object

(defprotocol IVertexBuffer
  (flip! [this] "make this buffer ready for use with OpenGL")
  (flipped? [this])
  (clear! [this])
  (position! [this position])
  (version [this]))

(deftype VertexBuffer [vertex-description usage ^Buffer buf ^long buf-items-per-vertex ^{:unsynchronized-mutable true} version]
  IVertexBuffer
  (flip! [this] (.flip buf) (set! version (inc version)) this)
  (flipped? [this] (= 0 (.position buf)))
  (clear! [this] (.clear buf) this)
  (position! [this position] (.position buf (int (* position buf-items-per-vertex))) this)
  (version [this] version)

  clojure.lang.Counted
  (count [this] (let [item-count (if (pos? (.position buf)) (.position buf) (.limit buf))]
                  (/ item-count buf-items-per-vertex))))

(defn buffer-item-byte-size
  ^long [^Buffer buffer]
  (condp instance? buffer
    ByteBuffer 1
    DoubleBuffer 8
    FloatBuffer 4
    IntBuffer 4
    LongBuffer 8
    ShortBuffer 2))

(defn- buffer-items-per-vertex
  ^long [^Buffer buffer vertex-description]
  (let [^long vertex-byte-size (:size vertex-description)
        buffer-item-byte-size (buffer-item-byte-size buffer)]
    (/ vertex-byte-size buffer-item-byte-size)))

(defn- buffer-size-in-bytes
  ^long [^Buffer buffer]
  (* (buffer-item-byte-size buffer) (.limit buffer)))

(defn wrap-vertex-buffer
  [vertex-description usage ^Buffer buffer]
  (let [buffer-items-per-vertex (buffer-items-per-vertex buffer vertex-description)]
    (->VertexBuffer vertex-description usage buffer buffer-items-per-vertex 0)))

(defn make-vertex-buffer
  [vertex-description usage ^long capacity]
  (let [nbytes (* capacity ^long (:size vertex-description))
        buf (doto (ByteBuffer/allocateDirect nbytes)
              (.order ByteOrder/LITTLE_ENDIAN))]
    (wrap-vertex-buffer vertex-description usage buf)))


;; vertex description

(defn- attribute-sizes
  [attributes]
  (map (fn [{:keys [^long components type]}] (* components ^long (type-sizes type))) attributes))

(defn- vertex-size
  [attributes]
  (reduce + (attribute-sizes attributes)))

(defn make-vertex-description
  [name attributes]
  {:name name
   :attributes attributes
   :size (vertex-size attributes)})


;; defvertex macro

(defn- attribute-components
  [attributes]
  (for [{:keys [name components] :as attribute} attributes
        n (range components)]
    (-> attribute
        (update :name str (nth ["-x" "-y" "-z" "-w"] n))
        (dissoc :components))))

(defn make-put-fn
  [attributes]
  (let [args (for [{:keys [name type] :as component} (attribute-components attributes)]
               (assoc component :arg (symbol name)))]
    `(fn [~(with-meta 'vbuf {:tag `VertexBuffer}) ~@(map :arg args)]
       (doto ~(with-meta '(.buf vbuf) {:tag `ByteBuffer})
         ~@(for [{:keys [arg type]} args]
             (let [arg (with-meta arg {:tag (case type
                                              :byte   `Byte
                                              :short  `Short
                                              :int    `Integer
                                              :float  `Float
                                              :double `Double
                                              :ubyte  `Byte
                                              :ushort `Short
                                              :uint   `Integer)})]
               (case type
                :byte   `(.put       ~arg)
                :short  `(.putShort  ~arg)
                :int    `(.putInt    ~arg)
                :float  `(.putFloat  ~arg)
                :double `(.putDouble ~arg)
                :ubyte  `(.put       (.byteValue  (Long. (bit-and ~arg 0xff))))
                :ushort `(.putShort  (.shortValue (Long. (bit-and ~arg 0xffff))))
                :uint   `(.putInt    (.intValue   (Long. (bit-and ~arg 0xffffffff))))))))
       ~'vbuf)))

(def ^:private type-component-counts
  {:vec1 1
   :vec2 2
   :vec3 3
   :vec4 4})

(defn- parse-attribute-definition
  [form]
  (let [[type nm & [normalized?]] form
        [prefix suffix]  (str/split (name type) #"\.")
        prefix           (keyword prefix)
        suffix           (keyword (or suffix "float"))
        num-components   (type-component-counts prefix)]
    (assert num-components (str type " is not a valid type name. It must start with vec1, vec2, vec3, or vec4."))
    (assert (get gl-types suffix) (str type " is not a valid type name. It must end with byte, short, int, float, or double. (Defaults to float if no suffix.)"))
    {:components num-components
     :type suffix
     :name (name nm)
     :normalized? (true? normalized?)}))

(defmacro defvertex
  [name & attribute-definitions]
  (let [attributes         (mapv parse-attribute-definition attribute-definitions)
        vertex-description (make-vertex-description name attributes)
        ctor-name          (symbol (str "->" name))
        put-name           (symbol (str name "-put!"))
        gen-put?           (not (:no-put (meta name)))]
    `(do
       (def ~name '~vertex-description)
       (defn ~ctor-name
         ([capacity#]
          (make-vertex-buffer '~vertex-description :static capacity#))
         ([capacity# usage#]
          (assert (contains? usage-types usage#) (format "usage must be %s" (str/join " or " (map str (keys usage-types)))))
          (make-vertex-buffer '~vertex-description usage# capacity#)))
       ~(when gen-put?
          `(def ~put-name ~(make-put-fn (:attributes vertex-description)))))))


;; GL stuff

(defn- vertex-locate-attribs
  [^GL2 gl shader attribs]
  (mapv #(shader/get-attrib-location shader gl (:name %)) attribs))

(defn- vertex-attrib-pointer
  [^GL2 gl attrib loc stride offset]
  (let [{:keys [name components type normalized?]} attrib]
    (when (not= -1 loc)
      (gl/gl-vertex-attrib-pointer gl ^int loc ^int components ^int (gl-types type) ^boolean normalized? ^int stride ^long offset))))

(defn- vertex-attrib-pointers
  [^GL2 gl attribs attrib-locs]
  (let [offsets (reductions + 0 (attribute-sizes attribs))
        stride  (vertex-size attribs)]
    (doall
      (map
        (fn [offset attrib loc]
          (vertex-attrib-pointer gl attrib loc stride offset))
        offsets attribs attrib-locs))))

(defn- vertex-enable-attribs
  [^GL2 gl locs]
  (doseq [l locs
          :when (not= l -1)]
    (gl/gl-enable-vertex-attrib-array gl l)))

(defn- vertex-disable-attribs
  [^GL2 gl locs]
  (doseq [l locs
          :when (not= l -1)]
    (gl/gl-disable-vertex-attrib-array gl l)))

(defn- find-attribute-index [attribute-name attributes]
  (util/first-index-where (fn [attribute] (= attribute-name (:name attribute)))
                          attributes))

(defn- request-vbo [^GL2 gl request-id ^VertexBuffer vertex-buffer shader]
  (scene-cache/request-object! ::vbo2 request-id gl {:vertex-buffer vertex-buffer :version (version vertex-buffer) :shader shader}))

(defn- bind-vertex-buffer-with-shader! [^GL2 gl request-id ^VertexBuffer vertex-buffer shader]
  (let [[vbo attrib-locs] (request-vbo gl request-id vertex-buffer shader)]
    (gl/gl-bind-buffer gl GL/GL_ARRAY_BUFFER vbo)
    (vertex-attrib-pointers gl (:attributes (.vertex-description vertex-buffer)) attrib-locs)
    (vertex-enable-attribs gl attrib-locs)))

(defn- unbind-vertex-buffer-with-shader! [^GL2 gl request-id ^VertexBuffer vertex-buffer shader]
  (let [[_ attrib-locs] (request-vbo gl request-id vertex-buffer shader)]
    (vertex-disable-attribs gl attrib-locs))
  (gl/gl-bind-buffer gl GL/GL_ARRAY_BUFFER 0))

(defn- bind-index-buffer! [^GL2 gl request-id ^IntBuffer index-buffer]
  ;; TODO: Feed in actual version. Perhaps deftype IndexBuffer with IntBuffer + version?
  (let [ibo (scene-cache/request-object! ::ibo2 request-id gl {:index-buffer index-buffer :version 0})]
    (gl/gl-bind-buffer gl GL/GL_ELEMENT_ARRAY_BUFFER ibo)))

(defn- unbind-index-buffer! [^GL2 gl]
  (gl/gl-bind-buffer gl GL/GL_ELEMENT_ARRAY_BUFFER 0))

(defrecord VertexBufferShaderLink [request-id ^VertexBuffer vertex-buffer shader]
  GlBind
  (bind [_this gl render-args]
    (bind-vertex-buffer-with-shader! gl request-id vertex-buffer shader))

  (unbind [_this gl render-args]
    (unbind-vertex-buffer-with-shader! gl request-id vertex-buffer shader)))

(defrecord VertexIndexBufferShaderLink [request-id ^VertexBuffer vertex-buffer ^IntBuffer index-buffer shader]
  GlBind
  (bind [_this gl render-args]
    (bind-vertex-buffer-with-shader! gl request-id vertex-buffer shader)
    (bind-index-buffer! gl request-id index-buffer))

  (unbind [_this gl render-args]
    (unbind-vertex-buffer-with-shader! gl request-id vertex-buffer shader)
    (unbind-index-buffer! gl)))

(defn use-with
  ([request-id ^VertexBuffer vertex-buffer shader]
   (->VertexBufferShaderLink request-id vertex-buffer shader))
  ([request-id ^VertexBuffer vertex-buffer ^IntBuffer index-buffer shader]
   (->VertexIndexBufferShaderLink request-id vertex-buffer index-buffer shader)))

(defn- update-vbo [^GL2 gl [vbo _] data]
  (gl/gl-bind-buffer gl GL/GL_ARRAY_BUFFER vbo)
  (let [^VertexBuffer vbuf (:vertex-buffer data)
        ^Buffer buf (.buf vbuf)
        shader (:shader data)
        attributes (:attributes (.vertex-description vbuf))
        attrib-locs (vertex-locate-attribs gl shader attributes)]
    (assert (flipped? vbuf) "VertexBuffer must be flipped before use.")
    (gl/gl-buffer-data ^GL2 gl GL/GL_ARRAY_BUFFER (buffer-size-in-bytes buf) buf (usage-types (.usage vbuf)))
    [vbo attrib-locs]))

(defn- make-vbo [^GL2 gl data]
  (let [vbo (first (gl/gl-gen-buffers gl 1))]
    (update-vbo gl [vbo nil] data)))

(defn- destroy-vbos [^GL2 gl objs _]
  (apply gl/gl-delete-buffers gl (map first objs)))

(scene-cache/register-object-cache! ::vbo2 make-vbo update-vbo destroy-vbos)

(defn- update-ibo [^GL2 gl ibo data]
  (gl/gl-bind-buffer gl GL/GL_ELEMENT_ARRAY_BUFFER ibo)
  (let [^IntBuffer int-buffer (:index-buffer data)
        count (.remaining int-buffer)
        size (* count Buffers/SIZEOF_INT)]
    (gl/gl-buffer-data ^GL2 gl GL/GL_ELEMENT_ARRAY_BUFFER size int-buffer GL2/GL_STATIC_DRAW))
  ibo)

(defn- make-ibo [^GL2 gl data]
  (let [ibo (first (gl/gl-gen-buffers gl 1))]
    (update-ibo gl ibo data)))

(defn- destroy-ibos [^GL2 gl ibos _]
  (apply gl/gl-delete-buffers gl ibos))

(scene-cache/register-object-cache! ::ibo2 make-ibo update-ibo destroy-ibos)
