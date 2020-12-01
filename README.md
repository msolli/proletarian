# Proletarian

A durable job queuing and worker system for Clojure backed by PostgreSQL.

Use Proletarian for asynchronously executing tasks in the background. It's
useful for offloading long-running tasks from the request thread in user-facing
web applications. What kind of tasks? Anything that takes more than a few
milliseconds:

 - sending emails
 - making HTTP calls to external system
 - updating search indexes
 - batch imports and exports

If you're already using PostgreSQL as your main database, there are some very
nice advantages to having your job queue in PostgreSQL as well:

- Using a transaction, you can atomically commit changes to the database along
with the queueing of the job. A common use-case for this is sending an email
after some action in the web application, e.g. when a user creates an account
you want to send them a confirmation email. You want the creation of the
account, and the enqueuing of the email job to either succeed or fail together.
This is sometimes called the Outbox Pattern in distributed computing literature.
- Similarly, in your worker job, if you're doing some processing that involves
writing to the database, the changes will only be committed if the job as a
whole succeeds. The job will be removed from the queue in the same transaction.

## Installation

Proletarian works with your existing PostgreSQL database. You'll have to create
two database tables, one for queueing jobs, and one for keeping a record of
finished jobs. These are defined in `database/proletarian-schema.sql `. Before
using the library you must install these tables in your database. There are many
ways you can do this. You are probably already using a migration library like
[Flyway](https://flywaydb.org/) or
[Migratus](https://github.com/yogthos/migratus). Copy the contents of the schema
file into a migration file. You can change the PostgreSQL schema and table
names, but then you'll need to provide the `:proletarian/job-table` and
`:proletarian/archived-job-table` options to `create-worker-controller`.

## Usage

Here is basic example, showing the creation of a queue worker in
one namespace, and the enqueuing of a job in another namespace:

```clojure
(ns your-app.workers
  "You'll probably want to use a component state library (Component, Integrant,
   Mount, or some such) for managing the worker state. For this example we're
   just def-ing the worker. We're also imagining you have a db namespace that
   manages the database connection, for example with next.jdbc. All we need is
   a javax.sql.DataSource, though."
  (:require [proletarian.worker :as worker]
            [your-app.db :as db]))

(def email-worker (worker/create-worker-controller db/data-source))

(worker/start! email-worker)
```

```clojure
(ns your-app.handlers
  "Let's say this is a namespace where you handle web requests. We're going to
   handle the request, write something to the database, and enqueue a job.
   We'll do this in a transaction with a little bit of help from next.jdbc."
  (:require [next.jdbc :as jdbc]
            [proletarian.job :as job]))

(defn some-handler [system request]
  (jdbc/with-transaction [tx (:db system)]
    ,,, ;; Do some business logic here
    ,,, ;; Write some result to the database
    ;; Enqueue the job:
    (job/enqueue! tx ::confirmation-email
      {:email email-address, :other-data-1 :foo, :other-data-2 :bar})
    ;; Return a response
    response))

;; Implement the proletarian.job/handle! multimethod for the job type.
(defmethod job/handle! ::confirmation-email
  [context job-type {:keys [email-address other-data-1 other-data-2]}]
  ;; Send the mail and do other time-consuming work here.
)
```

### Queue Workers

A _queue worker_ is a process that works off a given named queue. It can have
one or several _worker threads_, working in parallel. The worker threads pick
jobs off a queue and run them. While there are jobs to be processed, the workers
will work on them continuously until the queue is empty. Then they will poll the
queue at a configurable interval.

There is a default queue, `:proletarian/default`, which is the one used by
`job/enqueue!` and `worker/create-worker-controller` if no queue is specified in
the options.

You can create as many queue workers as you like, consuming jobs from different
queues. The jobs will all live in the same table, but are differentiated by the
queue name. The parameters you provide when setting up the queue workers, like
the polling interval and the number of worker threads (ie. the number of
parallel worker instances that are polling the queue and working on jobs), will
in effect control the priority of the jobs on the different queues.

A queue worker is local to one machine only. If you have several machines acting
as job processing instances, they will each have queue worker processes running.
The parallelization factor for a given queue will be the number of queue worker
processes (on different machines) multiplied by the number of threads in each
queue worker.

### Job Handlers

```clojure
(require '[proletarian.job :as job])

(defmethod job/handle! ::the-job-type
  [context job-type payload]
  ;; Do the work here.
)

(defmethod job/retry-strategy ::the-job-type
  [job throwable]
  {:retries 4
   :delays [1000 5000]})
```

## Acknowledgements

Many thanks for [Christian Johansen](https://github.com/cjohansen/) for help
with designing the library and database schema.

Hat tip to the creators of [MessageDB](https://github.com/message-db/message-db)
for inspiration how to do the example database install scripts. 

## License

Copyright © 2020 Martin Solli

Distributed under the Eclipse Public License, same as Clojure.
