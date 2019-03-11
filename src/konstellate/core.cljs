(ns konstellate.core
  (:require
    recurrent.core
    recurrent.drivers.vdom
    [clojure.string :as string]
    [konstellate.components :as components]
    [konstellate.editor.core :as editor]
    [konstellate.exporter :as exporter]
    [konstellate.graffle.core :as graffle]
    [recurrent.state :as state]
    [ulmus.signal :as ulmus]))

(comment def initial-state
  {:preferences {}
   :selected-nodes #{}
   :workspaces
   {:gensym {:canonical {:name "Foo"
                         :yaml {:gensym-a {}}}
             :edited {:name "Foo"
                      :yaml  {:gensym-b {:foo "bar"}}}}
    :gensym1 {:edited {:name "Bar"}}}})

(def initial-state {:selected-nodes #{}
                    :workspaces {}})

(defn Main
  [props sources]
  (let [definitions-$ (ulmus/map (fn [swagger-text]
                                   (:definitions (js->clj
                                                   (.parse js/JSON swagger-text)
                                                   :keywordize-keys true)))
                                 ((:swagger-$ sources) [:get]))

        title-bar (components/TitleBar {}
                                       {:recurrent/dom-$ (:recurrent/dom-$ sources)})
        menu (components/FloatingMenu
               {}
               (merge
                 (select-keys sources [:recurrent/dom-$])
                 {:pos-$ (ulmus/signal-of {:top "80px" :right "32px"})
                  :open?-$ (ulmus/reduce not false ((:recurrent/dom-$ sources) ".more" "click"))
                  :items-$ (ulmus/signal-of ["Export To Yaml" "Export To Kustomize" "Export To Helm"])}))

        selected-nodes-$ (ulmus/map :selected-nodes (:recurrent/state-$ sources))

        workspaces-$
        (ulmus/map 
          (fn [state]
            (into {}
                  (map (fn [[id workspace]]
                         [id {:name (get-in workspace [:edited :name])
                              :yaml (get-in workspace [:edited :yaml])
                              :dirty? (not= (get-in workspace [:canonical :yaml])
                                            (get-in workspace [:edited :yaml]))}])
                       (:workspaces state))))
          (:recurrent/state-$ sources))


        workspace-graffle-$ (ulmus/reduce (fn [acc [gained lost]]
                                            (merge acc
                                                   (into 
                                                     {}
                                                     (map (fn [k] [k ((state/isolate graffle/Graffle
                                                                                      [:workspaces k :edited :yaml])
                                                                       {} 
                                                                       (assoc 
                                                                         (select-keys sources [:recurrent/dom-$ :recurrent/state-$])
                                                                         :selected-nodes-$ selected-nodes-$))]) gained))))
                                          {}
                                          (ulmus/distinct
                                            (ulmus/map #(map keys %)
                                                       (ulmus/changed-keys workspaces-$))))

        workspace-list
        (components/WorkspaceList
          {} 
          {:recurrent/dom-$ (:recurrent/dom-$ sources)
           :selected-nodes-$ (ulmus/map :selected-nodes (:recurrent/state-$ sources))
           :workspaces-$ workspaces-$})

        selected-graffle-$
        (ulmus/map
          #(apply get %) (ulmus/zip workspace-graffle-$ (:selected-$ workspace-list)))

        kind-picker
        (editor/KindPicker
          {} 
          (assoc
            (select-keys sources
                         [:recurrent/dom-$
                          :swagger-$])
            :definitions-$ definitions-$))

        editor-$
        (ulmus/merge
          (ulmus/map (fn [[kind workspace]]
                       ((state/isolate editor/Editor
                                       [:workspaces
                                        workspace
                                        :edited
                                        :yaml
                                        (keyword (gensym))])
                        {:kind (:property kind)}
                        (assoc
                          (select-keys sources [:recurrent/dom-$
                                                :recurrent/state-$
                                                :swagger-$])
                          :definitions-$ definitions-$)))
                     (ulmus/distinct
                       (ulmus/filter
                         #(every? identity %)
                         (ulmus/zip
                           (:selected-$ kind-picker)
                           (ulmus/sample-on
                             (:selected-$ workspace-list)
                             (:selected-$ kind-picker))))))
          (ulmus/map (fn [edit-id]
                       (let [path [:workspaces
                                   @(:selected-$ workspace-list)
                                   :edited
                                   :yaml
                                   edit-id]
                             r (get-in @(:recurrent/state-$ sources) path)]
                       ((state/isolate editor/Editor path)
                        {:kind 
                         (str 
                           "io.k8s.api."
                           (if (string/includes? (:apiVersion r) "/")
                             (string/replace (:apiVersion r) "/" ".")
                             (str "core." (:apiVersion r)))
                           "."
                           (:kind r))
                         :initial-value r}
                        (assoc
                          (select-keys sources [:recurrent/dom-$
                                                :recurrent/state-$
                                                :swagger-$])
                          :definitions-$ definitions-$))))
                     (ulmus/sample-on
                       (ulmus/map
                         first
                         (ulmus/map :selected-nodes (:recurrent/state-$ sources)))
                       ((:recurrent/dom-$ sources) ".button.edit-resource" "click"))))

        side-panel
        (components/SidePanel
          {}
          {:dom-$ 
           (ulmus/map (fn [workspace-list-dom]
                        [:div {:class "workspaces"}
                         [:h4 {} "Workspaces"]
                         workspace-list-dom
                         [:div {:class "add-workspace"}
                          [:div {} "Add Workspace"]
                          [:icon {:class "material-icons"}
                           "add"]]])
                      (:recurrent/dom-$ workspace-list))
           :recurrent/dom-$ (:recurrent/dom-$ sources)})

        info-panel-open?-$
           (ulmus/map 
             #(not (empty? %))
             (ulmus/pickmap :selected-nodes-$ selected-graffle-$))

        info-panel
        (components/InfoPanel
          {}
          {:dom-$ (ulmus/map (fn [resource]
                               `[:div {}
                                 [:div {:class "heading"}
                                  [:h3 {} ~(get-in resource [:metadata :name])]
                                  [:div {:class "edit"} "Edit"]]
                                 [:div {:class "info"}
                                  [:h4 {} "Kind"]
                                  [:div {} ~(:kind resource)]]])
                             (ulmus/map #(first (vals %))
                                        (ulmus/pickmap :selected-resources-$ selected-graffle-$)))
           :open?-$ info-panel-open?-$
           :recurrent/dom-$ (:recurrent/dom-$ sources)})]

    (ulmus/subscribe!
      (ulmus/merge
        ((:recurrent/dom-$ sources) ".workspace-label" "dragstart")
        ((:recurrent/dom-$ sources) ".workspace-label-resource" "dragstart"))
      (fn [e]
        (.stopPropagation e)
        (.setData (.-dataTransfer e)
                  "text" 
                  (.getAttribute (.-currentTarget e) "data-id"))))

    (ulmus/subscribe!
      ((:recurrent/dom-$ sources) ".graffle" "dragover")
      (fn [e]
        (.preventDefault e)))

    (ulmus/subscribe!
      ((:recurrent/dom-$ sources) ".floating-menu-item.Export-Kustomize" "click")
      (fn []
        (exporter/save-kustomize!
          (map (fn [[k workspace]]
                 (:edited workspace))
               (:workspaces
                 @(:recurrent/state-$ sources))))))

    (ulmus/subscribe!
      ((:recurrent/dom-$ sources) ".floating-menu-item.Export-Helm" "click")
      (fn []
        (exporter/save-helm!
          (map (fn [[k workspace]]
                 (:edited workspace))
               (:workspaces
                 @(:recurrent/state-$ sources))))))


    {:swagger-$ (ulmus/signal-of [:get])
     :state-$ (:recurrent/state-$ sources)
     :recurrent/state-$ (ulmus/merge
                          (ulmus/signal-of (fn [] initial-state))
                          (ulmus/pickmap :recurrent/state-$ editor-$)
                          (ulmus/map (fn [selected]
                                       (fn [state]
                                         (assoc state
                                                :selected-nodes selected)))
                                     (ulmus/distinct
                                       (ulmus/pickmap :selected-nodes-$ selected-graffle-$)))
                          (ulmus/map (fn [e]
                                       (fn [state]
                                         (let [id (keyword (.getAttribute (.-currentTarget e) "data-id"))]
                                           (assoc state
                                                  :selected-nodes
                                                  #{id}))))
                                     ((:recurrent/dom-$ sources) ".workspace-label-resource" "click"))
                          (ulmus/map (fn [e]
                                       (fn [state]
                                         (let [from-id (keyword (.getData (.-dataTransfer e) "text"))
                                               src-yaml
                                               (into {}
                                                     (map (fn [[k v]] [k (with-meta v {:konstellate/antecedant from-id})])
                                                          (get-in state [:workspaces
                                                                         from-id
                                                                         :edited
                                                                         :yaml])))]
                                           (update-in state
                                                      [:workspaces
                                                       @(:selected-$ workspace-list)
                                                       :edited
                                                       :yaml]
                                                      #(merge % src-yaml)))))
                                     ((:recurrent/dom-$ sources) ".graffle" "drop"))
                          (ulmus/map (fn [name-change]
                                       (fn [state]
                                         (assoc-in state
                                                   [:workspaces
                                                    (:id name-change)
                                                    :edited
                                                    :name]
                                                   (:new-value name-change))))
                                     (:rename-$ workspace-list))
                          (ulmus/map (fn [id]
                                       (fn [state]
                                         (update-in state [:workspaces]
                                                    (fn [workloads]
                                                      (dissoc workloads id)))))
                                     (:delete-$ workspace-list))
                          (ulmus/map (fn [] 
                                       (fn [state]
                                         (assoc-in
                                           state
                                           [:workspaces
                                            (keyword (gensym))]
                                           {:edited {:name "New Workspace"
                                                     :yaml {}}})))
                                     ((:recurrent/dom-$ sources)
                                      ".add-workspace"
                                      "click")))
     :recurrent/dom-$
     (ulmus/choose
       (ulmus/start-with!
         :workspace
         (ulmus/merge
           (ulmus/map (constantly :kind-picker)
                      ((:recurrent/dom-$ sources) ".add-resource" "click"))
           (ulmus/map (constantly :editor)
                      (ulmus/merge
                        ((:recurrent/dom-$ sources) ".button.edit-resource" "click")
                        (:selected-$ kind-picker)))
           (ulmus/map (constantly :workspace)
                      (ulmus/pickmap :save-$ editor-$))))
       {:workspace
        (ulmus/map
          (fn [[title-bar-dom side-panel-dom info-panel-dom menu-dom info-panel-open? graffle]]
            [:div {:class "main"}
             [:div {:class (str "action-button add-resource " (if info-panel-open? "panel-open"))} "+"]
             title-bar-dom
             menu-dom
             [:div {:class "main-content"}
              side-panel-dom
              [:div {:class "graffle"} graffle]
              info-panel-dom]])
          (ulmus/distinct
            (ulmus/zip (:recurrent/dom-$ title-bar)
                       (:recurrent/dom-$ side-panel)
                       (:recurrent/dom-$ info-panel)
                       (:recurrent/dom-$ menu)
                       info-panel-open?-$
                       (ulmus/pickmap :recurrent/dom-$ selected-graffle-$))))
        :editor (ulmus/pickmap :recurrent/dom-$ editor-$)
        :kind-picker (:recurrent/dom-$ kind-picker)})}))

(defn start!
  []
  (let [swagger-path "https://raw.githubusercontent.com/kubernetes/kubernetes/master/api/openapi-spec/swagger.json"] 
    (recurrent.core/start!
      (state/with-state Main)
      {}
      {:swagger-$                                                                      (recurrent.drivers.http/create!                                                   swagger-path {:with-credentials? false}) 
       :recurrent/dom-$ (recurrent.drivers.vdom/for-id! "app")})))

(set! (.-onerror js/window) #(println %))

