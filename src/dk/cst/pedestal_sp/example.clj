(ns dk.cst.pedestal-sp.example
  (:require [clojure.set :as set]
            [hiccup.core :as hiccup]
            [io.pedestal.interceptor :as ic]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [dk.cst.pedestal-sp :as sp]
            [dk.cst.pedestal-sp.interceptors :as sp-ic]))

(def conf
  (sp/expand-conf {:app-name   "Example app"                ; EntityId in meta, ProviderName in request
                   :sp-url     "https://localhost:4433"
                   :idp-url    "https://localhost:7000"
                   :idp-cert   (slurp "/Users/rqf595/Code/temp/saml-test/node_modules/saml-idp/idp-public-cert.pem")
                   :credential {:alias    "mylocalsp"
                                :filename "/Users/rqf595/Code/temp/saml-test/keystore.jks"
                                :password (System/getenv "KEYSTORE_PASS")}}))

(defn login-page
  "Example login page handler. Specifying the query-param RelayState will
  redirect there after successful SAML authentication."
  [conf]
  (ic/interceptor
    {:name  ::login-page
     :enter (fn [{:keys [request] :as ctx}]
              (let [{:keys [query-params session]} request
                    {:keys [app-name
                            acs-url
                            paths]} conf
                    {:keys [saml-meta
                            saml-response
                            saml-assertions
                            saml-logout]} paths
                    logged-in? (sp/authenticated? session)]
                (assoc ctx
                  :response {:status  200
                             :headers {"Content-Type" "text/html"}
                             :body    (hiccup/html
                                        [:html
                                         [:body
                                          [:h1 app-name]
                                          [:p "Example login form for logging in through an IdP."]
                                          (if logged-in?
                                            [:form {:action saml-logout
                                                    :method "post"}
                                             [:input {:type  "hidden"
                                                      :name  "RelayState"
                                                      :value "/"}]
                                             [:button {:type "submit"}
                                              "Log out"]]
                                            [:form {:action acs-url}
                                             (when-let [relay-state (:RelayState query-params)]
                                               [:input {:type  "hidden"
                                                        :name  "RelayState"
                                                        :value relay-state}])
                                             [:button {:type "submit"}
                                              "Log in"]])
                                          [:h2 "Available resources:"]
                                          [:ul
                                           [:li
                                            [:a {:href saml-meta}
                                             "SAML metadata"]]
                                           (if (sp-ic/permit? ctx saml-response)
                                             [:li
                                              [:a {:href saml-response}
                                               "IdP response"]]
                                             [:li "⚠️ "
                                              [:a {:href saml-response}
                                               [:del "IdP response"]]])
                                           (if (sp-ic/permit? ctx saml-assertions)
                                             [:li
                                              [:a {:href saml-assertions}
                                               "User assertions"]]
                                             [:li "⚠️ "
                                              [:a {:href saml-assertions}
                                               [:del "User assertions"]]])]]])})))}))

(defn example-routes
  [conf]
  #{["/" :get [(sp-ic/session conf) (login-page conf)] :route-name ::login]})

(def routes
  (route/expand-routes
    (set/union (example-routes conf)
               (sp/saml-routes conf))))

(def service-map
  (let [home (System/getProperty "user.home")
        jks  (str home "/_certifiable_certs/localhost-1d070e4/dev-server.jks")]
    {::http/routes            routes
     ::http/type              :jetty
     ::http/port              8080

     ;; Development-only keystore created using Bruce Hauman's Certifiable.
     ;; https://github.com/bhauman/certifiable#quick-start-command-line-usage
     ::http/container-options {:ssl?         true
                               :ssl-port     4433           ; ports below 1024 require root permissions
                               :keystore     jks
                               :key-password "password"}}))

(defonce server (atom nil))

(defn start []
  (reset! server (-> service-map
                     (assoc ::http/join? false)
                     (http/create-server)
                     (http/start))))

(defn stop []
  (http/stop @server))

(defn restart []
  (when @server
    (stop))
  (start))

(comment
  (restart)
  (start)
  (stop)

  ;; Currently, there's a print-related bug with the SAML StateManager...
  (dissoc conf :state-manager)
  @(:state-manager conf))
