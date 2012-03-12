;;
;; Provides the transition table that models the webmachine decision
;; tree.
;;
(ns com.tnrglobal.bishop.flow
  (:use [clojure.java.io])
  (:import [org.apache.commons.codec.digest DigestUtils]
           [java.io ByteArrayOutputStream]))

(defn decide
  "Calls the provided test function (test-fn). If the function's
  return value matches the true-condition value then the true-fn is
  returned. If the test function returns a number (indicating an HTTP
  response code) then that response code is returned. In all other
  cases the false-fn is returned. If no true-condition value is
  supplied then boolean true is used."
  ([test-fn true-condition true-fn false-fn]

     (let [result (test-fn)]
       (cond (= true-condition result)
             true-fn

             (number? result)
             result

             :else
             false-fn)))
  ([test-fn true-fn false-fn]
     (decide test-fn true true-fn false-fn)))

(defn apply-callback
  "Invokes the provided callback function on the supplied resource."
  [request resource callback]
  ((callback (:handlers resource)) request))

(defn return-code
  "Returns a function that returns a sequence including the response
  code, the request, the response (with the status correctly set to
  the provided code) and a map of state data representing the decision
  flow. This function represents the end of the run."
  [code request response state]
  [code request (assoc response :status code) state])

;; utility methods

(defn response-200
  "Returns a function that will return a 200 response code and add the
  provided node (a keyword) to the state."
  [request response state node]
  #(return-code 200 request response (assoc state node true)))

(defn response-error
  "Returns a function that will return an error response code and add
  the provide node (a keyword) to the state."
  [code request response state node]
  #(return-code code request response (assoc state node false)))

(defn key-to-upstring
  "Returns a String containing the uppercase name of the provided
  key."
  [key]
  (.toUpperCase (name key)))

(defn list-keys-to-upstring
  "Returns a comma separated list of upper-case Strings, each one the
  name of one of the provided keys."
  [keys]
  (apply str (interpose ", " (for [key keys] (key-to-upstring key)))))

(defn header-value
  "Returns the value for the specified request header."
  [header headers]
  (first (some (fn [[header-in value]]
                 (if (= header header-in)
                   value))
               headers)))

;; states

;;(response-200 request response state :b11)

(defn b6
  [resource request response state]
  (decide #(apply-callback request resource :valid-content-headers?)
          true
          (response-200 request response state :b6)
          (response-error 501 request response state :b6)))

(defn b7
  [resource request response state]
  (decide #(apply-callback request resource :forbidden?)
          true
          (response-error 403 request response state :b7)
          #(b6 resource request response (assoc state :b6 true))))

(defn b8
  [resource request response state]
  (let [result (#(apply-callback request resource :is-authorized?))]
    (cond

      (= true result)
      #(b7 resource request response (assoc state :b8 true))

      (instance? String result)
      #(b7 resource request
           (assoc-in response
                     [:headers "www-authenticate"]
                     result)
           (assoc state :b8 result))

      :else
      (response-error 401 request response state :b8))))

(defn b9b
  [resource request response state]
  (decide #(apply-callback request resource :malformed-request?)
          true
          (response-error 400 request response state :b9b)
          #(b8 resource request response (assoc state :b9b false))))

(defn b9a
  [resource request response state]
  (let [valid (apply-callback request resource :validate-content-checksum)]

    (cond

      valid
      #(b9b resource request response (assoc state :b9a true))

      (nil? valid)
      (if (= (header-value "content-md5" (:headers request))
             (DigestUtils/md5Hex (with-open [reader-this (reader (:body request))
                                             buffer (ByteArrayOutputStream.)]
                                   (copy reader-this buffer)
                                   (.toByteArray buffer))))

        #(b9b resource request response (assoc state :b9a true))

        (response-error 400 request
                        (assoc response :body
                               "Content-MD5 header does not match request body")
                        state :b9a))

      :else
      (response-error 400 request
                      (assoc :body response
                             "Content-MD5 header does not match request body")
                      state :b9a))))

(defn b9
  [resource request response state]
  (decide #(some (fn [[head]]
                   (= "content-md5" head))
                 (:headers request))
          true
          #(b9a resource request response (assoc state :b9 true))
          #(b9b resource request response (assoc state :b9 false))))

(defn b10
  [resource request response state]
  (decide #(some (fn [method-in]
                   (= (:request-method request) method-in))
                 (apply-callback request resource :allowed-methods))
          true
          #(b9 resource request response (assoc state :b10 true))
          (response-error
           405 request
           (assoc-in response [:headers "allow"]
                     (list-keys-to-upstring
                      (apply-callback request resource :allowed-methods)))
           state :b10)))

(defn b11
  [resource request response state]
  (decide #(apply-callback request resource :uri-too-long?)
          true
          (response-error 414 request response state :b11)
          #(b10 resource request response (assoc state :b11 false))))

(defn b12
  [resource request response state]
  (decide #(some (fn [method-in]
                   (= (:request-method request) method-in))
                 (apply-callback request resource :known-methods))
          true
          #(b11 resource request response (assoc state :b12 true))
          (response-error 501 request response state :b12)))

(defn b13
  "Is the resource available?"
  [resource request response state]
  (decide #(apply-callback request resource :service-available?)
          true
          #(b12 resource request response (assoc state :b13 true))
          #(response-error 503 request response state :b13)))

(defn start
  "This function is the first stage in our processing tree. It will
  pass the resource and request onto node B13 with an empty response
  and the current machine state."
  [resource request]
  #(b13 resource request {} {}))

;; other functions

(defn respond-error
  "This function provides an endpoint for our processing pipeline, it
  returns the final error response map for the request."
  [[code request response state] resource]

  (assoc response :body (pr-str state)))

(defn respond
  "This function provides an endpoint for our processing pipeline, it
  returns the final response map for the request."
  [[code request response state] resource]

  (if (= 200 code)

    ;; get a handle on our response
    (let [resource-this (:response resource)]

      (cond

        ;; the resource contains a map of content types and return
        ;; values or functions
        (map? resource-this)
        (let [responder (resource-this "text/html")]

          (cond

            ;; invoke the response function
            (fn? responder)
            (assoc response :body (responder request))

            ;; return the response value
            :else
            (assoc response :body responder)))

        ;; the resource is a halt
        (and (coll? resource-this) (= :halt (first resource-this)))
        (assoc response :status (second resource-this))

        ;; the resource is an error
        (and (coll? resource-this) (= :error (first resource-this)))
        (merge response {:status 500
                         :body (second resource-this)})

        ;; we can't handle this resource
        :else
        (assoc response
          :body ((:error (:handlers resource)) code request response state))))

    ;; we have an error response code
    (assoc response
      :body ((:error (:handlers resource)) code request response state))))

(defn run
  "Applies the provided request and resource to our flow state
  machine. At the end of processing, a map will be returned containing
  the response object for the client."
  [request resource]

  (respond

   ;; apply the resource and request to our state machine yeilding a
   ;; sequence containing the response code, request, response and
   ;; machine state
   (trampoline #(start resource request))

   resource))
