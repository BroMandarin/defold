(ns editor.graph-view
  (:require [cljfx.api :as fx]
            [cljfx.fx.circle :as fx.circle]
            [cljfx.fx.group :as fx.group]
            [cljfx.fx.pane :as fx.pane]
            [cljfx.fx.polyline :as fx.polyline]
            [cljfx.fx.rectangle :as fx.rectangle]
            [cljfx.fx.scene :as fx.scene]
            [cljfx.fx.text :as fx.text]
            [cljfx.fx.v-box :as fx.v-box]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [editor.colors :as colors]
            [editor.fxui :as fxui]
            [editor.ui :as ui]
            [internal.util :as util])
  (:import [java.util Base64]
           [javafx.event ActionEvent]
           [javafx.geometry Point2D]
           [javafx.scene Node Scene]
           [javafx.scene.layout Background]
           [javafx.scene.paint Color]
           [javafx.scene.input ScrollEvent ZoomEvent]
           [javafx.stage Stage]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(s/def ::id (s/with-gen some? gen/int))
(s/def ::title string?)
(s/def ::color ::fxui/color)
(s/def ::position ::fxui/point)
(s/def ::jack (s/keys :req [::id ::title] :opt [::text-color ::socket-color ::plug-color]))
(s/def ::inputs (s/every ::jack :kind vector?))
(s/def ::outputs (s/every ::jack :kind vector?))
(s/def ::node (s/keys :req [::id ::title ::position] :opt [::color ::inputs ::outputs]))
(s/def ::from-position ::position)
(s/def ::to-position ::position)
(s/def ::connection (s/keys :req [::id ::from-position ::to-position] :opt [::color]))
(s/def ::nodes (s/every ::node :kind vector?))
(s/def ::connections (s/every ::connection :kind vector?))
(s/def ::scroll-offset ::fxui/point)
(s/def ::zoom-factor (s/and double? pos?))
(s/def ::graph (s/keys :opt [::nodes ::connections ::scroll-offset ::zoom-factor]))
(s/def ::view-data (s/keys :opt [::graph]))

(def ^{:tag 'double} node-width 200.0)
(def ^:private ^{:tag 'double} node-margin-width 20.0)
(def ^:private ^{:tag 'double} node-unit-height 20.0)
(def ^:private ^{:tag 'double} node-unit-text-y 14.0)
(def ^:private ^{:tag 'double} socket-stroke-width 2.0)
(def ^:private ^{:tag 'double} socket-radius 4.0)
(def ^:private ^{:tag 'double} connection-stroke-width 3.0)
(def ^:private ^{:tag 'double} connection-kink-width 20.0)

(def ^:private graph-background-color (ui/clj->color colors/scene-background))

(def ^:private default-node-color Color/DARKGREEN)
(def ^:private default-input-color Color/YELLOW)
(def ^:private default-output-color Color/CYAN)
(def ^:private default-connection-color (Color/valueOf "#464c55"))
(def ^:private default-socket-color graph-background-color)
(def default-plug-color default-connection-color)

(def ^:private graph-background (Background/fill graph-background-color))

(defn- data->fx-type-with-key [fx-type data]
  (-> data
      (dissoc ::id)
      (assoc :fx/type fx-type
             :fx/key (::id data))))

;; TODO: Move to styling/stylesheets/graph-view.sass.
(def ^:private ^String inline-stylesheet "
.graph-view-toolbar {
  -fx-background-color: -df-component
}

.graph-view-node-background {
  -fx-fill: -df-component-darker;
  -fx-opacity: 0.9;
}

.graph-view-node-title Rectangle {
}

.graph-view-node-title Text {
  -fx-fill: -df-text-selected;
}

.graph-view-node-input Text {
  -fx-font-size: 13px;
}

.graph-view-node-output Text {
  -fx-font-size: 13px;
}
")

(def ^:private inline-stylesheet-data-url
  (str "data:text/css;base64,"
       (.encodeToString (Base64/getUrlEncoder)
                        (.getBytes inline-stylesheet))))

(def ^:private node-unit-clip
  {:fx/type fx.rectangle/lifecycle
   :width node-width
   :height node-unit-height})

(defn- input [{::keys [title text-color socket-color plug-color] :as props}]
  (-> props
      (dissoc ::title ::text-color ::socket-color ::plug-color)
      (assoc :fx/type fx.group/lifecycle
             :style-class "graph-view-node-input"
             :children [{:fx/type fx.text/lifecycle
                         :x node-margin-width
                         :y node-unit-text-y
                         :text title
                         :fill (or text-color default-input-color)
                         :clip node-unit-clip}
                        {:fx/type fx.circle/lifecycle
                         :fill (or plug-color socket-color default-socket-color)
                         :stroke (or socket-color default-socket-color)
                         :stroke-width socket-stroke-width
                         :center-x (/ node-margin-width 2.0)
                         :center-y (/ node-unit-height 2.0)
                         :radius socket-radius}])))

(defn- output [{::keys [title text-color socket-color plug-color] :as props}]
  (-> props
      (dissoc ::title ::text-color ::socket-color ::plug-color)
      (assoc :fx/type fx.group/lifecycle
             :style-class "graph-view-node-output"
             :children [{:fx/type fx.text/lifecycle
                         :y node-unit-text-y
                         :wrapping-width (- node-width node-margin-width)
                         :text-alignment :right
                         :text title
                         :fill (or text-color default-output-color)
                         :clip node-unit-clip}
                        {:fx/type fx.circle/lifecycle
                         :fill (or plug-color socket-color default-socket-color)
                         :stroke (or socket-color default-socket-color)
                         :stroke-width socket-stroke-width
                         :center-x (- node-width (/ node-margin-width 2.0))
                         :center-y (/ node-unit-height 2.0)
                         :radius socket-radius}])))

(defn- jack-group [{:keys [jacks jack-fx-type] :as props}]
  (-> props
      (dissoc :jacks :jack-fx-type)
      (assoc :fx/type fx.group/lifecycle
             :children (into []
                             (map-indexed (fn [^long index jack]
                                            (-> jack
                                                (dissoc ::id)
                                                (assoc :fx/type jack-fx-type
                                                       :fx/key (::id jack)
                                                       :layout-y (* index node-unit-height)))))
                             jacks))))

(defn- node [{::keys [title position color inputs outputs] :as props}]
  ;; TODO: experiment with inputs-source, inputs-fn to query NodeType directly?
  (let [input-count (count inputs)
        output-count (count outputs)
        node-unit-count (+ 1 input-count output-count) ; One unit for the title and one per jack.
        node-height (* node-unit-count node-unit-height)]
    (-> props
        (dissoc ::title ::position ::color ::inputs ::outputs)
        (assoc :fx/type fx.group/lifecycle
               :layout-x (ui/point-x position)
               :layout-y (ui/point-y position)
               :children (cond-> [{:fx/type fx.group/lifecycle
                                   :fx/key :node/title
                                   :style-class "graph-view-node-title"
                                   :children [{:fx/type fx.rectangle/lifecycle
                                               :fill (or color default-node-color)
                                               :width node-width
                                               :height node-unit-height}
                                              {:fx/type fx.text/lifecycle
                                               :x node-margin-width
                                               :y node-unit-text-y
                                               :text title
                                               :clip node-unit-clip}]}
                                  {:fx/type fx.rectangle/lifecycle
                                   :fx/key :node/background
                                   :style-class "graph-view-node-background"
                                   :layout-y node-unit-height ; Offset by one unit for the title.
                                   :width node-width
                                   :height (- node-height node-unit-height)}]

                                 (seq inputs)
                                 (conj {:fx/type jack-group
                                        :fx/key :node/inputs
                                        :layout-y node-unit-height ; Offset by one unit for the title.
                                        :jacks inputs
                                        :jack-fx-type input})

                                 (seq outputs)
                                 (conj {:fx/type jack-group
                                        :fx/key :node/outputs
                                        :layout-y (* (inc input-count) node-unit-height) ; Offset by one unit for the title and one per input.
                                        :jacks outputs
                                        :jack-fx-type output}))))))

(defn- connection [{::keys [^Point2D from-position ^Point2D to-position color] :as props}]
  (let [from-x (.getX from-position)
        from-y (.getY from-position)
        to-x (.getX to-position)
        to-y (.getY to-position)
        start-x (Double/valueOf from-x)
        start-y (Double/valueOf from-y)
        end-x (Double/valueOf to-x)
        end-y (Double/valueOf to-y)
        out-x (Double/valueOf (+ from-x connection-kink-width))
        out-y start-y
        in-x (Double/valueOf (- to-x connection-kink-width))
        in-y end-y]
    (-> props
        (dissoc ::from-position ::to-position ::color)
        (assoc :fx/type fx.polyline/lifecycle
               :stroke (or color default-connection-color)
               :stroke-width connection-stroke-width
               :points [start-x start-y out-x out-y in-x in-y end-x end-y]))))

(defn- graph [{::keys [nodes connections scroll-offset zoom-factor]
               :or {scroll-offset Point2D/ZERO
                    zoom-factor 1.0}
               :as props}]
  (-> props
      (dissoc ::nodes ::connections ::scroll-offset ::zoom-factor)
      (assoc :fx/type fx.group/lifecycle
             :translate-x (ui/point-x scroll-offset)
             :translate-y (ui/point-y scroll-offset)
             :scale-x zoom-factor
             :scale-y zoom-factor
             :children [{:fx/type fx.group/lifecycle
                         :children (into []
                                         (map (partial data->fx-type-with-key connection))
                                         connections)}
                        {:fx/type fx.group/lifecycle
                         :children (into []
                                         (map (partial data->fx-type-with-key node))
                                         nodes)}])))

(defn- view-map-desc [view-data]
  {:fx/type fx.v-box/lifecycle
   :children [{:fx/type fx.v-box/lifecycle
               :style-class "graph-view-toolbar"
               :children [{:fx/type fxui/button
                           :text "Close"
                           :cancel-button true
                           :on-action {:event/type ::close-button}}]}
              {:fx/type fx.pane/lifecycle
               :background graph-background
               :v-box/vgrow :always
               :pref-width 800
               :pref-height 600
               :on-scroll {:event/type ::scroll}
               :on-zoom {:event/type ::zoom}
               :children [(assoc (::graph view-data) :fx/type graph)]}]})

(defn- window-map-desc [view-data]
  {:fx/type fxui/stage
   :title "Graph View"
   :modality :none
   :showing true
   :on-hidden {:event/type ::window-closed}
   :scene {:fx/type fx.scene/lifecycle
           :stylesheets ["dialogs.css" inline-stylesheet-data-url]
           :root (view-map-desc view-data)}})

(defn- handle-scroll-event! [view-data-atom ^ScrollEvent scroll-event]
  (swap! view-data-atom update-in [::graph ::scroll-offset]
         (fn [scroll-offset]
           (let [^Point2D scroll-offset (or scroll-offset Point2D/ZERO)]
             (.add scroll-offset
                   (.getDeltaX scroll-event)
                   (.getDeltaY scroll-event)))))
  (.consume scroll-event))

(defn- handle-zoom-event! [view-data-atom ^ZoomEvent zoom-event]
  (swap! view-data-atom update-in [::graph ::zoom-factor]
         (fn [zoom-factor]
           (let [^double zoom-factor (or zoom-factor 1.0)]
             (+ zoom-factor
                (.getZoomFactor zoom-event)))))
  (.consume zoom-event))

(defn- view-event-handler [view-data-atom event]
  (case (:event/type event)
    ::scroll (handle-scroll-event! view-data-atom (:fx/event event))
    ::zoom (handle-zoom-event! view-data-atom (:fx/event event))))

(defn- handle-close-button-event! [^ActionEvent action-event]
  (let [^Node event-source (.getSource action-event)
        ^Scene scene (.getScene event-source)
        ^Stage stage (.getWindow scene)]
    (.close stage)
    (.consume action-event)))

(defn- handle-window-closed-event! [view-data-atom renderer]
  (fx/unmount-renderer view-data-atom renderer))

(defn- window-event-handler [view-data-atom renderer-ref event]
  (case (:event/type event)
    ::close-button (handle-close-button-event! (:fx/event event))
    ::window-closed (handle-window-closed-event! view-data-atom @renderer-ref)
    (view-event-handler view-data-atom event)))

(defn- mount-window! [view-data-atom error-handler]
  (let [renderer-ref (volatile! nil)
        renderer (fx/create-renderer
                   :error-handler error-handler
                   :middleware (fx/wrap-map-desc window-map-desc)
                   :opts {:fx.opt/map-event-handler (partial window-event-handler view-data-atom renderer-ref)})]
    (vreset! renderer-ref renderer)
    (fx/mount-renderer view-data-atom renderer)
    renderer))

(defn make-view-data-atom [view-data]
  (s/assert ::view-data view-data)
  (doto (atom view-data)
    (set-validator! (fn [view-data]
                      (or (s/valid? ::view-data view-data)
                          (s/explain ::view-data view-data))))))

(defn show-window! [view-data-atom error-handler]
  (mount-window! view-data-atom error-handler))

(defn node-input-jack-position
  ^Point2D [node-props input-id]
  (when-some [input-index (util/first-index-where #(= input-id (::id %)) (::inputs node-props))]
    (let [node-unit-index (+ 1 ^long input-index) ; Offset by one unit for the title.
          node-position (::position node-props Point2D/ZERO)
          jack-x (+ (ui/point-x node-position) (/ node-margin-width 2.0))
          jack-y (+ (ui/point-y node-position) (* node-unit-index node-unit-height) (/ node-unit-height 2.0))]
      (ui/point jack-x jack-y))))

(defn node-output-jack-position
  ^Point2D [node-props output-id]
  (when-some [output-index (util/first-index-where #(= output-id (::id %)) (::outputs node-props))]
    (let [input-count (count (::inputs node-props))
          node-unit-index (+ 1 input-count ^long output-index) ; Offset by one unit for the title.
          node-position (::position node-props Point2D/ZERO)
          jack-x (- (+ (ui/point-x node-position) node-width) (/ node-margin-width 2.0))
          jack-y (+ (ui/point-y node-position) (* node-unit-index node-unit-height) (/ node-unit-height 2.0))]
      (ui/point jack-x jack-y))))
