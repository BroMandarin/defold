(ns dynamo.selection
  (:require [dynamo.graph :as g]
            [dynamo.types :as t]))

(g/defnode Selection
  (input selected-nodes ['t/Node])

  (output selection      t/Any     (g/fnk [selected-nodes] (mapv :_id selected-nodes)))
  (output selection-node Selection (g/fnk [self] self)))

(defmulti selected-nodes (fn [x] (class x)))
