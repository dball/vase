(ns com.cognitect.vase.actions
  "Functions to construct interceptors dynamically.

  The functions with names that end in '-action' compile Pedestal
  interceptors. These are the main public entry points, and are used
  by the `literals` namespace when loading Vase descriptors.

  Take care to avoid code generation during requst processing. It is
  time consuming, so it should be done at application startup time
  instead.

  Actions are created by emitting code (in the functions named
  `-exprs`) which gets evaluated to build an interceptor. Arguments to
  the `-exprs` functions are interpolated into the emitted code. These
  arguments can contain literals or one s-expression each.

  For example, `respond-action-exprs` has an argument `body` that will
  be used as the HTTP response body. It may be a string or an
  s-expression.

  When s-expression arguments are evaluated, they have some bindings
  available in scope:

  - `request` - contains the Pedestal request map
  - `context` - contains the Pedestal context map
  - request parameters captured from path-params or parsed body
    parameters _and_ named in the `params` seq

  E.g., if `params` holds `['userid]` and the request matched a route
  with \"/users/:userid\", then the s-expression will have the symbol
  `'userid` bound to the value of that request parameter."
  (:require [clojure.walk :as walk]
            [clojure.spec :as s]
            [datomic.api :as d]
            [io.pedestal.interceptor :as interceptor]
            [com.cognitect.vase.util :as util])
  (:import java.net.URLDecoder))

;; Code generation tools
;;
(defn decode-map
  "URL Decode the values of a Map
  This opens up the potential for non-sanitized input to be rendered."
  [m]
  (walk/postwalk
   #(cond-> %
      (string? %)
      (URLDecoder/decode %))
   m))

(defn coerce-arg-val
  ([v]
   (try
       (util/read-edn v)
       (catch Exception e v)))
  ([args k]
   (let [v (get args k)]
     (coerce-arg-val v)))
  ([args k default-v]
   (let [v (get args k default-v)]
     (coerce-arg-val v))))

(defn process-lookup-ref
  [r]
  (update r 0 keyword))

(defn process-id
  [entity-data]
  (cond-> entity-data
    (vector? (:db/id entity-data))
    (assoc :db/id (process-lookup-ref (:db/id entity-data)))

    (nil? (:db/id entity-data))
    (assoc :db/id (d/tempid :db.part/user))))

(defn process-assert
  [args]
  (mapv process-id args))

(defn process-retract
  [args]
  (mapv #(vector :db.fn/retractEntity (:db/id %)) args))

(def db-ops
  {:vase/assert-entity  `process-assert
   :vase/retract-entity `process-retract})

(defn tx-processor
  [op]
  (get db-ops op `process-assert))

(defn merged-parameters
  [request]
  (let [{:keys [path-params params json-params edn-params]} request]
    (merge (decode-map path-params) params json-params edn-params)))

(def eav (juxt :e :a :v))

;; @ohpauleez - is 'args' the right thing for the whitelist key here?
(defn apply-tx
  [conn tx-data args]
  {:whitelist
   args

   :transaction
   (->> tx-data
        (d/transact conn)
        deref
        (map eav))})

;; Building Interceptors
;;
(defn dynamic-interceptor
  "Build an interceptor/interceptor from a map of keys to
  expressions. The expressions will be evaluated and must evaluate to
  a function of 1 argument. At runtime the function will be called
  with a Pedestal context map."
  [name literal exprs]
  (with-meta
    (interceptor/interceptor
     (merge
      {:name name}
      (util/map-vals eval exprs)))
    {:action-literal literal}))

;; Auxiliary functions for interceptor internals
;;
(defn coerce-params
  [req-params coercions]
  (reduce (fn [params-acc k]
             ;; Params are all string values, which allows if-let to be safe to use
             ;; We can't use just `coerce-arg-value` because we don't want to add missing/not-found keys
             (if-let [v (get params-acc k)]
               (assoc params-acc k (coerce-arg-val v))
               params-acc))
           req-params
           coercions))

(defn bind
  [param-syms]
  (let [param-keys (mapv #(if (vector? %) (first %) %) param-syms)
        param-defaults (into {} (filter vector? param-syms))]
    `{:keys ~(or param-keys [])
      :or ~param-defaults}))

;; Interceptors for common cases
;;
(defn respond-action-exprs
  "Return code for a Pedestal interceptor that will respond with a
  canned response. The same `body`, `status`, and `headers` arguments
  are returned for every HTTP request."
  [params edn-coerce body status headers]
  `(fn [{~'request :request :as ~'context}]
     (let [req-params#    (merged-parameters ~'request)
           ~(bind params) (coerce-params req-params# ~(mapv util/ensure-keyword (or edn-coerce [])))
           resp#          (util/response
                           ~(or body "")
                           ~headers
                           ~(or status 200))]
       (assoc ~'context :response resp#))))

(comment
  (clojure.pprint/pprint
    (respond-action-exprs '[url-thing]
                          '[url-thing]
                          '(str "You said: " url-thing " which is a " (type url-thing))
                          200 {}))
  )

(defn respond-action
  "Return a Pedestal interceptor that responds with a canned
  response."
  [name params edn-coerce body status headers]
  (dynamic-interceptor
   name
   :respond
   {:enter
    (respond-action-exprs params edn-coerce body status headers)

    :action-literal
    :vase/respond}))

(defn redirect-action-exprs
  "Return code for a Pedestal interceptor function that returns a
  redirect response."
  [params body status headers url]
  `(fn [{~'request :request :as ~'context}]
     (let [req-params#    (merged-parameters ~'request)
           ~(bind params) req-params#
           resp#          (util/response
                           ~(or body "")
                           (merge ~headers {"Location" ~(or url "")})
                           ~(or status 302))]
       (assoc ~'context :response resp#))))

(defn redirect-action
  "Return a Pedestal interceptor that redirects to a static URL."
  [name params body status headers url]
  (dynamic-interceptor
   name
   :redirect
   {:enter
    (redirect-action-exprs params body status headers url)

    :action-literal
    :vase/redirect}))

(defn validate-action-exprs
  "Return code for a Pedestal interceptor function that performs
  clojure.spec validation on the parameters."
  [params headers spec]
  `(fn [{~'request :request :as ~'context}]
     (let [req-params#    (merged-parameters ~'request)
           ~(bind params) req-params#
           problems#      (mapv
                           #(dissoc % :pred)
                           (:clojure.spec/problems
                            (clojure.spec/explain-data ~spec req-params#)))
           resp#          (util/response
                           problems#
                           ~headers
                           (util/status-code problems# (:errors ~'context)))]
       (if (or (empty? (:io.pedestal.interceptor.chain/queue ~'context))
               (seq problems#))
         (assoc ~'context :response resp#)
         ~'context))))

(defn validate-action
  "Returns a Pedestal interceptor that performs validations on the
  parameters.

  The response body will be a list of data structures as returned by
  clojure.spec/explain-data."
  [name params headers spec]
  (dynamic-interceptor
   name
   :validate
   {:enter
    (validate-action-exprs params headers spec)

    :action-literal
    :vase/validate}))

(defn query-action-exprs
  "Return code for a Pedestal interceptor function that performs a
  Datomic query.

  `query` holds a query expression in any supported Datomic
  format. Required.

  `variables` is a vector of the query variables (expressed as
  keywords) that should arrive in the Pedestal request map. These will
  be supplied to the query as inputs. May be nil.  Values within the
  `variables` vector may also be pair-vectors, in the form `[key default-value]`,
  allowing for default values if the key/keyword is not found in the request map.

  `coercions` is a set of variable names (expressed as keywords) that
  should be read as EDN values from the Pedestal request map. (I.e.,
  anything that needs to be converted from String to Date, Long, etc.)
  May be nil.

  `constants` is a vector of extra inputs to the query. These will be
  appended to the query inputs _after_ the variables. May be nil.

  `headers` is an expression that evaluates to a map of header
  name (string) to header value (string). May be nil."
  [query variables coercions constants headers]
  (let [args-sym (gensym 'args)]
    `(fn [{~'request :request :as ~'context}]
       (let [~args-sym      (merged-parameters ~'request)
             vals#          [~(mapcat
                               (fn [x]
                                 (let
                                   [[k default-v] (if (vector? x) x [x nil])]
                                   (if (contains? coercions k)
                                   `(coerce-arg-val ~args-sym ~k ~default-v)
                                   `(get ~args-sym ~k ~default-v))))
                                variables)]
             db#            (:db ~'request)
             query-params# (concat vals# ~constants)
             query-result#  (when (every? some? query-params#)
                              (apply d/q '~query db# query-params#))
             response-body# (if query-result#
                              (into [] query-result#)
                              (str
                                "Missing required query parameters; One or more parameters was `nil`."
                                "  Got: " (keys ~args-sym)
                                "  Required: " ~variables))
             resp#          (util/response
                             response-body#
                             ~headers
                             (if query-result#
                               (util/status-code response-body# (:errors ~'context))
                               400))]
         (assoc ~'context :response resp#)))))

(defn query-action
  "Returns a Pedestal interceptor that executes a Datomic query on
  entry."
  [name query variables coercions constants headers]
  (dynamic-interceptor
   name
   :query
   {:enter
    (query-action-exprs query variables coercions constants headers)

    :action-literal
    :vase/query}))

(defn transact-action-exprs
  "Return code for a Pedestal context function that executes a
  transaction.

  `properties` is a collection of keywords that name Datomic
  attributes. When an HTTP request arrives, these keywords are matched
  with their parameter values in the request to form an entity map.

  `db-op` may be either :vase/assert-entity, :vase/retract-entity, or
  nil. When `nil`, Vase will assume the transaction body is a
  collection of Datomic entity maps.

  `headers` is an expression that evaluates to a map of header
  name (string) to header value (string). May be nil."
  [properties db-op headers]
  `(fn [{~'request :request :as ~'context}]
     (let [args#          (merged-parameters ~'request)
           args#          (map
                           #(select-keys % ~(vec properties))
                           (get-in ~'request [:json-params :payload]))
           tx-data#       (~(tx-processor db-op) args#)
           conn#          (:conn ~'request)
           response-body# (apply-tx
                           conn#
                           tx-data#
                           args#)
           resp#          (util/response
                           response-body#
                           ~headers
                           (util/status-code response-body# (:errors ~'context)))]
       (assoc ~'context :response resp#))))

(defn transact-action
  "Returns a Pedestal interceptor that executes a Datomic transaction
  on entry."
  [name properties db-op headers]
  (dynamic-interceptor
   name
   :transact
   {:enter
    (transact-action-exprs properties db-op headers)

    :action-literal
    :vase/transact}))
