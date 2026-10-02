(ns curve.dom-api
  "The DOM operations the mounter needs. Implemented by the browser DOM
  (curve.dom) and by the headless DOM (curve.headless) used for tests and SSR.")

(defprotocol Dom
  (instantiate [d render] "Fresh root nodes (a vector) for a ctor's :render template.")
  (child-at [d node i] "i-th child node.")
  (text-node [d s])
  (replace-node! [d old new])
  (insert-before! [d parent node ref] "ref nil appends.")
  (remove-node! [d node])
  (parent-of [d node])
  (next-of [d node])
  (set-text! [d node s])
  (set-attr! [d el k v] "v nil removes. value/checked set the property.")
  (listen! [d el type f] "Returns an unlisten fn."))
