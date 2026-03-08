# Example B – Retries and Failure Handling

This example demonstrates how to use Proletarian's retry strategy and failed job
handling. We'll enqueue jobs that fail about half the time, and see how the Queue
Worker retries them and eventually gives up on the ones that keep failing.

To run this example, you'll need the same setup as [Example A](example-a.md):

- PostgreSQL, version 9.5 or later, installed locally.
- The Clojure `clj` command-line tool, version 1.10.1.697 or later, with support
  for the -X option.
  ([Installation instructions](https://clojure.org/guides/getting_started))
- Two terminal windows.
- [The Proletarian Git repo](https://github.com/msolli/proletarian) cloned to a
  local directory: `git clone git@github.com:msolli/proletarian.git`. We'll
  assume you're in this directory going forward.
- The example database, created with `make examples.db.install` (see
  [Example A](example-a.md) for details). If you've already run Example A, you
  might want to start fresh with `make examples.db.recreate`.

### 1. Run the Queue Worker

In one of your terminal windows, run this command to start the Queue Worker:
```shell
clj -X:examples example-b.worker/run
```

It should start a process that polls the default queue for jobs every 5 seconds:

```
Number of jobs in :proletarian/default queue: 0
Number of jobs in proletarian.jobs table: 0
Number of jobs in proletarian.archived_jobs table: 0

Starting workers for :proletarian/default queue
Polling interval: 5 seconds. Worker threads: 1
:proletarian.worker/polling-for-jobs {:worker-thread-id 1, :proletarian.worker/queue-worker-id proletarian[:proletarian/default]}
:proletarian.worker/polling-for-jobs {:worker-thread-id 1, :proletarian.worker/queue-worker-id proletarian[:proletarian/default]}
:proletarian.worker/polling-for-jobs {:worker-thread-id 1, :proletarian.worker/queue-worker-id proletarian[:proletarian/default]}
[...and so forth, until you press Ctrl-C]
```

Leave this process running while you continue to step 2.

The worker in this example is configured with a few things that Example A didn't
have:

- A **retry strategy function** (`:proletarian/retry-strategy-fn`) that tells
  Proletarian how many times to retry a failed job and how long to wait between
  retries.
- A **failed job function** (`:proletarian/failed-job-fn`) that gets called when
  a job has exhausted all its retries and is permanently failed.
- A **polling error handler** (`:proletarian/on-polling-error`) that logs polling
  errors and allows the worker to keep running.
- A **JVM shutdown hook** (`:proletarian/install-jvm-shutdown-hook?`) that
  ensures the worker shuts down gracefully when you press Ctrl-C, and prints a
  summary of successful and failed jobs.

You can also configure the number of worker threads and the polling interval:
```shell
clj -X:examples example-b.worker/run :worker-threads 2 :polling-interval 1
```

The code for this can be found in
the [`example-b.worker`](https://github.com/msolli/proletarian/blob/main/examples/example_b/worker.clj)
namespace.

### 2. Enqueue some jobs

In your other terminal window, run this command to enqueue a batch of 10 jobs:
```shell
clj -X:examples example-b.enqueue-jobs/run
```

It should add 10 jobs to the default queue and print the job details:

```
Adding 10 new jobs to :proletarian/default queue:
{:job-id #uuid "...",
 :job-type :example-b.enqueue-jobs/sometimes-failing,
 :payload {:batch-no 1, :counter 0}}
{:job-id #uuid "...",
 :job-type :example-b.enqueue-jobs/sometimes-failing,
 :payload {:batch-no 1, :counter 1}}
[...8 more jobs...]

Press Enter to enqueue more jobs (Ctrl-C to exit)
```

You can press Enter to enqueue another batch of 10 jobs, or Ctrl-C to exit.

The code for this can be found in
the [`example-b.enqueue-jobs`](https://github.com/msolli/proletarian/blob/main/examples/example_b/enqueue_jobs.clj)
namespace.

### 3. Watch the retries

Switch back to the worker terminal window. You should see the worker pick up the
jobs and start processing them. Each job calls a function that sleeps for 0.5–1.5
seconds and then fails about 50% of the time. When a job succeeds, you'll see
output like this:

```
:proletarian.worker/handling-job {:job-type :example-b.enqueue-jobs/sometimes-failing, :attempt 1, ...}
[  1/0 "Running job :example-b.enqueue-jobs/sometimes-failing. Payload:"]
[  1/0 {:batch-no 1, :counter 0}]
[  1/0 "This will fail 50 % of the time and take on average a second:"]
[  1/0 "Phew, it didn't fail. Done."]
:proletarian.worker/job-finished {:job-type :example-b.enqueue-jobs/sometimes-failing, :attempt 1, ...}
```

When a job fails, Proletarian will retry it according to the retry strategy. The
retry strategy in this example allows up to 2 retries, with a delay that comes
from the exception's `:retry-after` data (simulating a common backoff pattern in
web APIs). You'll see the job get re-scheduled and tried again:

```
:proletarian.worker/handling-job {..., :attempt 1, ...}
[  1/1 "Running job :example-b.enqueue-jobs/sometimes-failing. Payload:"]
[  1/1 {:batch-no 1, :counter 1}]
[  1/1 "This will fail 50 % of the time and take on average a second:"]
:proletarian.worker/handle-job-exception {:exception #error {
 :cause This operation failed for some reason
 :data {:retry-after 726}
 :via
 [{:type clojure.lang.ExceptionInfo
   :message This operation failed for some reason
   :data {:retry-after 726}
   :at [example_b.enqueue_jobs$do_possibly_failing_thing_BANG_ invokeStatic enqueue_jobs.clj 40]}]
 :trace
 [...]}, ...}
:proletarian.retry/retrying {:retry-at #object[java.time.Instant ...], :retries-left 2, :attempt 1, ...}
```

If a job fails on all attempts (the initial try plus 2 retries = 3 attempts
total), the failed job function is called and the job is archived with a
`:failure` status:

```
:proletarian.retry/not-retrying {:retry-spec {:retries-left 0}, :attempt 3, ...}
[  1/2 "Job failed after 3 attempts (exception message: 'This operation failed for some reason')"]
```

### 4. Shut down and see the summary

When you're done, press Ctrl-C in the worker terminal. The shutdown hook will
stop the worker gracefully and print a summary with data collected from the
`proletarian.archived_job` table:

```
:proletarian.executor/completed-shutdown {:proletarian.worker/queue-worker-id proletarian[:proletarian/default]}
Number of successful jobs: 9
Number of failed jobs: 1
```

(Your numbers will vary depending on how lucky or unlucky your jobs were.)

### How the retry strategy works

The retry strategy function receives the job and the exception, and returns a map
with two keys:

```clojure
(defn retry-strategy
  [_job exception]
  (let [retry-after (-> exception (ex-data) :retry-after)]
    {:retries 2
     :delays [retry-after]}))
```

- `:retries` – the maximum number of retries (not counting the initial attempt).
- `:delays` – a sequence of delays in milliseconds between retries. If there are
  fewer delays than retries, the last delay is repeated.

In this example, the delay comes from the exception's `:retry-after` data. This
is a common pattern when dealing with external APIs that return backoff hints.
The retry strategy function has access to the full exception, so you can inspect
its data to make informed decisions about whether and when to retry.

### How the failed job function works

When a job has exhausted all its retries and is permanently failed, the failed
job function is called with the job map and the exception:

```clojure
(defn handle-failed-job!
  [{:proletarian.job/keys [payload attempts] :as _job} exception]
  ...)
```

This is where you would typically log the failure, send an alert, or take any
other action needed when a job can't be completed. The job is then archived with
a `:failure` status in the `proletarian.archived_job` table.
