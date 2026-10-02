(ns curve.dev
  "Development tooling (never in a production bundle).")

(defn reload-clj
  "shadow-cljs build hook: after a compile, reload on the JVM the .cljc
  namespaces that were just recompiled, so the server runs the same program
  tables as the browser. Use with curve.client/reload! as :after-load.
    :build-hooks [(curve.dev/reload-clj)]"
  {:shadow.build/stage :compile-finish}
  [build-state]
  (let [compiled (get-in build-state [:shadow.build/build-info :compiled])
        nses (for [rid compiled
                   :let [{:keys [ns resource-name]} (get-in build-state [:sources rid])]
                   :when (and ns resource-name (.endsWith ^String resource-name ".cljc"))]
               ns)]
    (doseq [ns nses]
      (try (require ns :reload)
           (catch Throwable e
             (println "curve.dev/reload-clj:" ns (ex-message e)))))
    build-state))
