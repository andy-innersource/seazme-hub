(ns seazme.hub.biz
  (:require [ring.util.http-response :refer [ok not-found accepted bad-request]]
            [clj-uuid :as uuid]
            [seazme.common.hbase :as hb]
            [seazme.common.config :as config]
            [digest :as d]
            [clojure.tools.logging :as log]
            [clj-time.format :as tf] [clj-time.core :as tr] [clj-time.coerce :as te]))

;;
;; constants
;;
(def app-cf-name :app)
(def self-cf-name :self)

(def time-span   (* 1000 60 60 24))
(def time-offset (* 1000 60 15))
(defn next-to[to] (* (quot (+ to time-span) time-span) time-span))

;;
;; those are constants per API version, never change in mid-flight
;;
(def ts-key (tf/formatter "yyyyMMddHHmmss"))
(defn jts-now[] (te/to-long (tr/now)))
;;(defn ts-unparse[dt] (tf/unparse ts-key dt))
;;(defn ts-unparse-now[] (ts-unparse (tr/now)))
;;(defn ts-parse[s] (tf/parse ts-key s))

;;
;; interface
;;
(defn hashed-passwords[]  (-> config/config :hashed-passwords))
(defn sso-config[] (-> config/config :sso))

(defn status[op headers]
  (log/info op headers)
  (ok (let [cs (hb/status)]
        {:dead-servers (->> cs .getDeadServerNames seq (map (memfn getHostname)) (clojure.string/join ","))
         :load-average (->> cs .getAverageLoad)
         ;;internal stats, sessions, sizes, counts, etc
         :comment "wip: API response is being worked on"}
        ))
  )

(defn GET-applications-apikey[op headers session]
  (log/info op headers (-> session :saml  :assertions))
  (ok {:apikey "wip" :comment "wip"})
  )

(defn POST-applications-intake[op headers description kind bu instance notification_contacts base_url]
  (log/info op headers description bu kind instance notification_contacts base_url)
  (let [id (str (uuid/v1))
        jts (jts-now)
        app-key id
        app-meta {:headers headers
                  :id id
                  :key id
                  :created jts
                  :comment "your app has been successfully created"}
        app-cf {:expires 0 ;;in Julian TS, 0 never
                :description description
                :kind kind
                :bu bu
                :instance instance
                :notification-contacts notification_contacts
                :base-url base_url
                :meta app-meta}]
    (hb/store* "datahub:apps" app-key self-cf-name app-cf)
    (ok (select-keys app-meta [:id :comment])))
  )

;;TODO inject random 404 or similar to make sure clients are resilent
;;TODO during truncation, HBASE tables are disabled and scan fails, handle it, also org.apache.hadoop.hbase.client.RetriesExhaustedWithDetailsException upon further store
;;TODO consider caching apps and sessions
;;TODO validate type of source
;;TODO revise HTTP return codes, leave it in doc https://github.com/metosin/ring-http-response/blob/master/src/ring/util/http_response.clj
;;TODO common error handling for HBASE
;;TODO common retry for certain HBASE errors
;;TODO check if there are other sessions going on
;;TODO  '"last-updated" 1510274303492' leaks into structures after reading it and storing again
(defmulti POST-intake-sessions-handler (fn [jts command description past-sessions] command))
(defmethod POST-intake-sessions-handler "scan" [jts command description past-sessions]
  [ok
   {:expires 0 ;;in Julian TS, 0 never
    :command command
    :description description
    :range {:from 0 :to jts}}
   "request accepted"])


(defmethod POST-intake-sessions-handler "update" [jts command description past-sessions]
  ;;reset-content - too early
  (if (empty? @past-sessions)
    [bad-request {} "no prior submission or initial scan in progress"]
    (let [to (-> @past-sessions first second :self :range :to)
          nto (next-to to)
          behind-time (- jts nto time-offset)]
      (if (neg? behind-time)
        (let [try-again-in (quot (- behind-time) (* 1000 60))]
          [accepted
           {:try-again-in try-again-in
            :time-span time-span
            :time-offset time-offset
            }
           (format "%s; cannot create session for future updates, try in %d minutes, perfectly good time for a cup of tea :-)" description try-again-in)])
        [ok
         {:expires 0 ;;in Julian TS, 0 never
          :command command
          :description description
          :time-span time-span
          :time-offset time-offset
          :range {:from to :to nto}}
         "request accepted"]
        ))))

(defmethod POST-intake-sessions-handler :default [jts command description past-sessions] [not-found {} (str "invalid command:" command)])

;;TODO check expired session, implement expiration
;;TODO check if cancel/submit and/or expired
(defn POST-intake-sessions[op headers app-id command description]
  (log/info op headers app-id command description)
  (if-let [app-cf (hb/find-by* "datahub:apps" app-id self-cf-name)]
   (let [id (str (uuid/v1))
         jts (jts-now)
         session-tsx (format "%011x" jts);;will be good till "2525-06-07T12:18:37.938Z"
         session-key (format "%s\\%s" session-tsx id)
         kind (:kind app-cf)
         past-sessions  (delay (->> (hb/scan* "datahub:intake-sessions" :reverse? true :lazy? true)
                                    (filter (comp (partial = app-id) :id :meta :app second))
                                    (filter (comp (partial = kind) :kind :app second));;this is redundant check since app-id must match kind as well
                                    (filter (comp (partial = "submit") :action :meta :self second))))
         [http-code session-cf comment2] (POST-intake-sessions-handler jts command description past-sessions)
         session-meta {:headers headers
                       :id id
                       :key session-key
                       :tsx session-tsx
                       :created jts
                       :comment comment2
                       :action "request"}]
     (if (= http-code ok)
       (do
         ;;TODO store multiple CF at once
         (hb/store* "datahub:intake-sessions" session-key self-cf-name (assoc session-cf :meta session-meta))
         (hb/store* "datahub:intake-sessions" session-key app-cf-name app-cf)
         (http-code (conj {:key session-key} (select-keys session-cf [:expires :command :range])(select-keys session-meta [:comment]))))
       (http-code (conj {:comment comment2}))))
   (not-found {:comment "app not found"})))

;;TODO check for \\ in the input of course
;;TODO factor in version of the source as well
;;TODO move that to config
(defn get-path-from-doc[app-kind payload]
  (case app-kind
    "twiki" (format "%s\\%s" (-> payload :web) (-> payload :topic))
    "confluence" (format "%d\\%s" (-> payload :space :id) (-> payload :id))
    "answerhub" (format "%d\\%d" (-> payload :c_id) (-> payload :c_originalparent))
    "jira" (format "%s" (-> payload :id))
    "snow" (format "%s" (-> payload :number))
    "circlehd" (format "%s" (-> payload :media_id))
    ))


(defn POST-intake-sessions-_-document[op headers session-key payload]
  (log/info op headers session-key (count payload))
  ;;TODO find better way to find multiple CF
  ;;TODO check if cancel/submit and/or expired
  (if-let [self-cf (hb/find-by* "datahub:intake-sessions" session-key self-cf-name)]
    (if-let [app-cf (hb/find-by* "datahub:intake-sessions" session-key app-cf-name)]
      (let [path (get-path-from-doc (-> app-cf :kind) payload);;TODO verify path
            jts (jts-now)
            session-id (-> self-cf :meta :id)
            session-tsx (-> self-cf :meta :tsx)
            document-key (format "%02x\\%s\\%s" (rand-int 256) session-id path) ;;256 is hardcoded constant forever!
            document-meta {:headers headers
                           :session {:id session-id :key session-key :tsx session-tsx}
                           :key document-key
                           :created jts
                           :comment "the document has been posted"}]
        (log/info op self-cf app-cf path document-key)
        (hb/store* "datahub:intake-data" document-key self-cf-name {:payload payload :type "document" :meta document-meta})
        (hb/store* "datahub:intake-data" document-key app-cf-name app-cf)
        (ok (select-keys document-meta [:comment])))
      (not-found {:comment "app not found"}))
    (not-found {:comment "self not found"}))
  )

(defn POST-intake-sessions-_-cancel[op headers session-key]
  ;;TODO - check if session already canceled
  (log/info op headers session-key)
  (if-let [self-cf (hb/find-by* "datahub:intake-sessions" session-key self-cf-name)]
    (if-let [app-cf (hb/find-by* "datahub:intake-sessions" session-key app-cf-name)]
      (let [action "cancel"
            jts (jts-now)
            session-id (-> self-cf :meta :id)
            session-tsx (-> self-cf :meta :tsx)
            session-action-key (format "%s\\%s" session-key action)
            session-meta {:headers headers
                          :id session-id
                          :key session-action-key
                          :tsx session-tsx
                          :created jts
                          :comment "the session has been canceled"
                          :action action}]
        (hb/store* "datahub:intake-sessions" session-action-key self-cf-name (assoc self-cf :meta session-meta))
        (hb/store* "datahub:intake-sessions" session-action-key app-cf-name app-cf)
        (ok (select-keys session-meta [:comment]))
        )
      (not-found {:comment "app not found"}))
    (not-found {:comment "self not found"}))
  )

;;TODO - issue warning if there no documents/graphs uploaded
;;TODO - check if session already submitted
(defn POST-intake-sessions-_-submit[op headers session-key count2]
  (log/info op headers session-key count2)
  (if-let [self-cf (hb/find-by* "datahub:intake-sessions" session-key self-cf-name)]
    (if-let [app-cf (hb/find-by* "datahub:intake-sessions" session-key app-cf-name)]
      (let [action "submit"
            jts (jts-now)
            session-id (-> self-cf :meta :id)
            session-tsx (-> self-cf :meta :tsx)
            session-action-key (format "%s\\%s" session-key action)
            session-meta {:headers headers
                          :id session-id
                          :key session-action-key
                          :tsx session-tsx
                          :created jts;;TODO consider storing initial/submitted TS as well
                          :comment "the session has been submitted"
                          :action action}]
        (hb/store* "datahub:intake-sessions" session-action-key self-cf-name (assoc self-cf :count count2 :meta session-meta))
        (hb/store* "datahub:intake-sessions" session-action-key app-cf-name app-cf)
        (ok (select-keys session-meta [:comment]))
        )
      (not-found {:comment "app not found"}))
    (not-found {:comment "self not found"}))
  )
