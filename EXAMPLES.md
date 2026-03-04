# Integration DSL — Examples

All examples use free, public APIs with readily available API keys.

---

## 1. Weather → Slack (simplest possible)

Poll weather API every hour, post to Slack channel.

```kotlin
integration("weather-alerts") {

    val weather = adapter("openweather") {
        rest("https://api.openweathermap.org/data/2.5")
        auth { apiKey(param = "appid", secret("openweather-key")) }
    }

    val slack = adapter("slack") {
        rest("https://slack.com/api")
        auth { bearer(secret("slack-bot-token")) }
    }

    flow("hourly-weather") {
        every(1.hours)

        call(weather.get("/weather") {
            query("q" to "Berlin,DE", "units" to "metric")
        })

        transform {
            source["main.temp"]          to target["temp"]
            source["weather[0].description"] to target["desc"]
            source["main.temp"] format { "${it}C" } to target["display"]
        }

        filter { source["main.temp"].toDouble() > 35 || source["main.temp"].toDouble() < -10 }

        call(slack.post("/chat.postMessage") {
            body {
                json {
                    "channel" to "#alerts"
                    "text" to "Weather alert Berlin: ${mapped["display"]} — ${mapped["desc"]}"
                }
            }
        })
    }
}
```

**APIs:**
- OpenWeatherMap: https://openweathermap.org/api (free tier, 60 calls/min)
- Slack API: https://api.slack.com/methods/chat.postMessage

---

## 2. GitHub Issues → Linear (bidirectional sync)

Sync GitHub issues with Linear tasks. Demonstrates bidirectional sync with state tracking.

```kotlin
integration("github-linear") {

    val github = adapter("github") {
        rest("https://api.github.com")
        auth { bearer(secret("github-token")) }
        headers { "Accept" to "application/vnd.github.v3+json" }
        schema { openapi(url = "https://raw.githubusercontent.com/github/rest-api-description/main/descriptions/api.github.com/api.github.com.json") }
    }

    val linear = adapter("linear") {
        graphql("https://api.linear.app/graphql")
        auth { bearer(secret("linear-api-key")) }
        schema { introspect() }
    }

    state {
        store(sqlite("state.db"))
        mapping("issues") {
            key(github["issues.id"], linear["issues.id"])
            track("last_synced", "sync_hash")
        }
    }

    // GitHub → Linear
    flow("github-to-linear") {
        on(github.webhook("issues")) {
            events("opened", "edited", "closed", "reopened")
        }

        filter { source["action"] in listOf("opened", "edited", "closed", "reopened") }

        transform {
            source["issue.title"]       to target["title"]
            source["issue.body"]        to target["description"]
            source["issue.state"] mapped {
                "open"   to "Todo"
                "closed" to "Done"
            } to target["stateId"]
            source["issue.labels"].each { it["name"] } to target["labelIds"]
        }

        branch {
            on({ state.exists("issues", source["issue.id"]) }) then {
                // Update existing
                val linearId = state.lookup("issues", source["issue.id"])
                call(linear.mutate("issueUpdate") {
                    variable("id" to linearId)
                    input(mapped)
                })
            }
            otherwise {
                // Create new
                call(linear.mutate("issueCreate") {
                    variable("teamId" to secret("linear-team-id"))
                    input(mapped)
                })
                state.update("issues", source["issue.id"] to result["issueCreate.issue.id"])
            }
        }
    }

    // Linear → GitHub
    flow("linear-to-github") {
        on(linear.webhook("Issue"))

        filter { state.exists("issues", linear = source["data.id"]) }

        transform {
            source["data.title"]       to target["title"]
            source["data.description"] to target["body"]
            source["data.state.name"] mapped {
                "Done"       to "closed"
                "Cancelled"  to "closed"
                default      to "open"
            } to target["state"]
        }

        val githubId = state.lookup("issues", linear = source["data.id"])
        call(github.patch("/repos/{owner}/{repo}/issues/${githubId}") {
            body(mapped)
        })
    }
}
```

**APIs:**
- GitHub REST API: https://docs.github.com/en/rest (free, 5000 req/hour)
- Linear API: https://developers.linear.app/docs/graphql/working-with-the-graphql-api (free)

---

## 3. Stripe → Accounting Export + Exposed API

Listen for Stripe payment events, transform to accounting records, expose a REST API for the accounting system to pull data.

```kotlin
integration("stripe-accounting") {

    val stripe = adapter("stripe") {
        rest("https://api.stripe.com/v1")
        auth { bearer(secret("stripe-secret-key")) }
    }

    val db = adapter("db") {
        jdbc("sqlite:///data/accounting.db")
    }

    state {
        store(db)
        mapping("invoices") {
            key(stripe["invoices.id"], db["accounting_records.stripe_id"])
        }
    }

    flow("payment-received") {
        on(stripe.webhook("invoice.payment_succeeded"))

        verify { signature(header = "Stripe-Signature", secret("stripe-webhook-secret")) }

        transform {
            source["data.object.id"]              to target["stripe_id"]
            source["data.object.customer_email"]  to target["customer_email"]
            source["data.object.amount_paid"]     to target["amount"] format { (it.toLong() / 100.0).toString() }
            source["data.object.currency"]        to target["currency"] format { it.uppercase() }
            source["data.object.created"]         to target["date"] format { epochToIso(it) }
            source["data.object.lines.data"].each { line ->
                line["description"] to target["line_items"].append {
                    line["amount"] to it["amount"] format { (it.toLong() / 100.0).toString() }
                    line["description"] to it["description"]
                }
            }
            constant("stripe")                    to target["source"]
            now()                                 to target["synced_at"]
        }

        call(db.execute("""
            INSERT INTO accounting_records (stripe_id, customer_email, amount, currency, date, line_items, source, synced_at)
            VALUES (:stripe_id, :customer_email, :amount, :currency, :date, :line_items, :source, :synced_at)
            ON CONFLICT (stripe_id) DO UPDATE SET amount = :amount, synced_at = :synced_at
        """) { params(mapped) })
    }

    expose {
        rest {
            port(8080)
            basePath("/api/v1")
            auth { apiKey(header = "X-API-Key") }
            docs { openapi(title = "Accounting Integration", version = "1.0") }
        }

        endpoint(GET, "/records") {
            call(db.query("SELECT * FROM accounting_records ORDER BY date DESC LIMIT :limit") {
                param("limit" to queryParam("limit", default = "50"))
            })
            respond(200)
        }

        endpoint(GET, "/records/{id}") {
            call(db.query("SELECT * FROM accounting_records WHERE stripe_id = :id") {
                param("id" to pathParam("id"))
            })
            respond(200)
        }

        endpoint(GET, "/records/export") {
            call(db.query("SELECT * FROM accounting_records WHERE date BETWEEN :from AND :to") {
                param("from" to queryParam("from"))
                param("to" to queryParam("to"))
            })
            transform {
                source.each { record ->
                    record to target.csv {
                        column("Date", record["date"])
                        column("Customer", record["customer_email"])
                        column("Amount", record["amount"])
                        column("Currency", record["currency"])
                    }
                }
            }
            respond(200) { contentType("text/csv") }
        }

        endpoint(GET, "/health") {
            respond(200) {
                body {
                    json {
                        "status" to "ok"
                        "last_payment" to db.query("SELECT MAX(synced_at) FROM accounting_records").scalar()
                        "total_records" to db.query("SELECT COUNT(*) FROM accounting_records").scalar()
                    }
                }
            }
        }
    }
}
```

**APIs:**
- Stripe API: https://stripe.com/docs/api (test mode free, use `sk_test_*` keys)

---

## 4. RSS → Telegram Digest (simple, no auth)

Aggregate RSS feeds and send a daily digest to Telegram.

```kotlin
integration("news-digest") {

    val hn = adapter("hackernews") {
        rest("https://hacker-news.firebaseio.com/v0")
    }

    val telegram = adapter("telegram") {
        rest("https://api.telegram.org/bot${secret("telegram-bot-token")}")
    }

    flow("daily-digest") {
        cron("0 8 * * *")  // every day at 8am

        call(hn.get("/topstories.json"))

        transform {
            source.take(10) to target["story_ids"]
        }

        forEach(mapped["story_ids"]) { storyId ->
            call(hn.get("/item/${storyId}.json"))
            collect {
                source["title"] to it["title"]
                source["url"]   to it["url"]
                source["score"] to it["score"]
            }
        }

        transform {
            collected.sortedByDescending { it["score"] }
                .mapIndexed { i, story -> "${i+1}. ${story["title"]}\n${story["url"]}" }
                .joinToString("\n\n")
                to target["message"]
        }

        call(telegram.post("/sendMessage") {
            body {
                json {
                    "chat_id" to secret("telegram-chat-id")
                    "text" to mapped["message"]
                    "parse_mode" to "HTML"
                }
            }
        })
    }
}
```

**APIs:**
- HackerNews API: https://github.com/HackerNews/API (free, no auth)
- Telegram Bot API: https://core.telegram.org/bots/api (free)

---

## 5. Benetics ↔ Hero (the real project)

The integration that started it all.

```kotlin
integration("benetics-hero") {
    version(1)
    description("Bidirectional sync between Benetics AI and Hero Software ERP")

    secrets { env() }

    val hero = adapter("hero") {
        graphql("https://login.hero-software.de/api/external/v7/graphql")
        auth { bearer(secret("hero-api-key")) }
        schema { introspect() }
    }

    val benetics = adapter("benetics") {
        rest("https://public-api.benetics.io/v1")
        auth { bearer(secret("benetics-api-key")) }
        schema { openapi("specs/benetics-openapi.json") }
        webhooks {
            verify { hmac(header = "X-Signature", secret("benetics-webhook-secret")) }
        }
    }

    val db = adapter("db") {
        jdbc("sqlite:///data/sync-state.db")
    }

    state {
        store(db)
        mapping("projects") {
            key(hero["project_matches.id"], benetics["projects.id"])
            track("last_synced", "sync_hash")
        }
        mapping("members") {
            key(hero["contacts.phone_home"], benetics["members.phone_number"])
        }
    }

    // ──────────────────────────────────────
    // US1: Project sync (bidirectional)
    // ──────────────────────────────────────

    flow("hero-projects-poll") {
        description("Poll Hero for new/changed projects, sync to Benetics")
        every(5.minutes)

        call(hero.query("project_matches") {
            field("id", "project_nr", "measure { short, name }",
                  "customer { id, first_name, last_name, email }",
                  "address { street, city, zipcode }",
                  "current_project_match_status { status_code, name }")
        })

        onNewOrChanged(state.mapping("projects")) {
            transform {
                source["project_nr"]   to target["name"]
                source["address"].let {
                    "${it["street"]}, ${it["zipcode"]} ${it["city"]}"
                } to target["address"]
                source["current_project_match_status.name"] mapped {
                    "Abgeschlossen" to "archived"
                    "Storniert"     to "archived"
                    default         to "active"
                } to target["state"]
            }

            branch {
                on({ !state.exists("projects", hero = source["id"]) }) then {
                    call(benetics.post("/projects") { body(mapped) })
                    state.update("projects", source["id"] to result["id"])
                }
                otherwise {
                    val beneticsId = state.lookup("projects", hero = source["id"])
                    call(benetics.patch("/projects/${beneticsId}") { body(mapped) })
                    state.markSynced("projects", source["id"])
                }
            }
        }
    }

    flow("benetics-project-webhook") {
        description("Benetics project created/updated → sync to Hero")
        on(benetics.webhook("project.created", "project.updated"))

        transform {
            source["payload.project.name"]    to target["name"]
            source["payload.project.address"] to target["address"]
            source["payload.project.state"] mapped {
                "archived" to "Abgeschlossen"
                "active"   to "Offen"
            } to target["status"]
        }

        branch {
            on({ source["payload.event"] == "project.created" }) then {
                call(hero.mutate("create_project_match") { input(mapped) })
                state.update("projects", result["id"] to source["payload.project.id"])
            }
            otherwise {
                // update existing Hero project
                val heroId = state.lookup("projects", benetics = source["payload.project.id"])
                call(hero.mutate("update_project_match") {
                    variable("id" to heroId)
                    input(mapped)
                })
                state.markSynced("projects", benetics = source["payload.project.id"])
            }
        }
    }

    flow("sync-project-members") {
        description("Sync project members between Hero and Benetics via phone number")
        after(flow("hero-projects-poll"))

        forEach(state.allMappings("projects")) { projectMapping ->
            // Get Hero employees for this project
            call(hero.query("project_matches") {
                variable("ids" to listOf(projectMapping.heroId))
                field("customer { phone_home, first_name, last_name }")
            })

            // Get Benetics members for this project
            call(benetics.get("/projects/${projectMapping.beneticsId}/members"))

            // Match by phone number, add missing members
            diff(
                source = hero["customer.phone_home"],
                target = benetics["members.phone_number"],
                key = "phone_number"
            ) { missing ->
                val memberId = state.lookup("members", hero = missing["phone_home"])
                call(benetics.post("/projects/${projectMapping.beneticsId}/members") {
                    body { json { "user_id" to memberId; "role" to "member" } }
                })
            }
        }
    }

    // ──────────────────────────────────────
    // US2: Reports → Hero (Benetics → Hero)
    // ──────────────────────────────────────

    flow("report-pdf-to-hero") {
        description("Benetics report export → PDF to Hero project documents")
        on(benetics.webhook("export.created"))
        filter { source["payload.export.kind"] == "reports" }
        filter { source["payload.export.state.status"] == "completed" }

        val projectMapping = state.lookup("projects", benetics = source["payload.project.id"])

        download(benetics.get("/projects/${source["payload.project.id"]}/exports/${source["payload.export.id"]}/content"))

        upload(hero.documents) {
            projectId(projectMapping.heroId)
            folder("Benetics Rapporte")
            filename(source["payload.export.filename"])
        }

        log("Report ${source["payload.export.id"]} synced to Hero project ${projectMapping.heroId}")
    }

    flow("report-structured-to-hero") {
        description("Benetics report submission → structured data to Hero (V2)")
        on(benetics.webhook("report_submission.created"))

        call(benetics.get("/projects/${source["payload.project.id"]}/reports/${source["payload.report.id"]}/submissions/${source["payload.submission.id"]}"))

        transform {
            // Map structured report fields to Hero time/material entries
            source["values.Auftraggeber"]       to target["customer_name"]
            source["values.Arbeitsstunden"].each { row ->
                row["Mitarbeiter"] to target["time_entries"].append {
                    row["Datum"]   to it["date"]
                    row["Stunden"] to it["hours"]
                }
            }
            source["values.Material"].each { row ->
                row["Material / Maschine"] to target["material_entries"].append {
                    row["Menge"]           to it["quantity"]
                }
            }
        }

        forEach(mapped["time_entries"]) { entry ->
            call(hero.mutate("add_logbook_entry") {
                input {
                    "project_match_id" to state.lookup("projects", benetics = source["payload.project.id"]).heroId
                    "custom_title"     to "Zeiterfassung: ${entry["date"]}"
                    "custom_text"      to "${entry["hours"]}h — ${mapped["customer_name"]}"
                }
            })
        }
    }

    // ──────────────────────────────────────
    // US3: Tasks → Hero (Benetics → Hero)
    // ──────────────────────────────────────

    flow("task-pdf-to-hero") {
        description("Benetics task export → PDF with photos to Hero project documents")
        on(benetics.webhook("export.created"))
        filter { source["payload.export.kind"] == "tasks" }
        filter { source["payload.export.state.status"] == "completed" }

        val projectMapping = state.lookup("projects", benetics = source["payload.project.id"])

        download(benetics.get("/projects/${source["payload.project.id"]}/exports/${source["payload.export.id"]}/content"))

        upload(hero.documents) {
            projectId(projectMapping.heroId)
            folder("Benetics Aufgaben")
            filename(source["payload.export.filename"])
        }
    }

    flow("task-photos-to-hero") {
        description("Benetics task photos → Hero project images")
        on(benetics.webhook("task.created", "task.updated"))

        call(benetics.get("/projects/${source["payload.project.id"]}/tasks/${source["payload.task.id"]}"))

        filter { source["photos"].isNotEmpty() }

        val projectMapping = state.lookup("projects", benetics = source["payload.project.id"])

        forEach(source["photos"]) { photo ->
            download(benetics.get(photo["download_url"]))
            upload(hero.images) {
                projectId(projectMapping.heroId)
                filename("task-${source["payload.task.id"]}-${photo["id"]}.jpg")
            }
        }
    }

    // ──────────────────────────────────────
    // Exposed API
    // ──────────────────────────────────────

    expose {
        rest {
            port(8080)
            basePath("/api/v1")
            auth { apiKey(header = "X-API-Key") }
            docs { openapi(title = "Benetics-Hero Integration", version = "1.0") }
        }

        // Webhook receiver for Benetics
        endpoint(POST, "/webhooks/benetics") {
            verify { hmac(header = "X-Signature", secret("benetics-webhook-secret")) }
            route { event ->
                when {
                    event["event"] matches "project.*"            -> trigger(flow("benetics-project-webhook"))
                    event["event"] matches "export.created"       -> trigger(flow("report-pdf-to-hero"), flow("task-pdf-to-hero"))
                    event["event"] matches "report_submission.*"  -> trigger(flow("report-structured-to-hero"))
                    event["event"] matches "task.*"               -> trigger(flow("task-photos-to-hero"))
                }
            }
            respond(200)
        }

        // Manual triggers
        endpoint(POST, "/sync/projects") {
            trigger(flow("hero-projects-poll"))
            respond(202) { body { json { "status" to "sync started" } } }
        }

        // Status / health
        endpoint(GET, "/status") {
            respond(200) {
                body {
                    json {
                        "status"          to "ok"
                        "projects_synced" to state.count("projects")
                        "members_mapped"  to state.count("members")
                        "flows" to flows.map {
                            it.name to json {
                                "last_run"  to it.lastRun()
                                "status"    to it.lastStatus()
                                "run_count" to it.runCount()
                            }
                        }
                    }
                }
            }
        }

        metrics {
            path("/metrics")
            include(flowDuration, flowErrors, adapterLatency, webhookDelivery, syncedRecords)
        }
    }
}
```

**APIs:**
- Hero GraphQL: https://hero-software.de/api-doku/graphql-guide (API key from Hero support)
- Benetics REST: https://developer.benetics.io/reference (API key from Benetics)

---

## 6. Minimal: JSONPlaceholder → Console (for first test)

The simplest possible integration for testing the DSL framework itself.

```kotlin
integration("hello-world") {

    val api = adapter("jsonplaceholder") {
        rest("https://jsonplaceholder.typicode.com")
    }

    flow("fetch-users") {
        manual()

        call(api.get("/users"))

        transform {
            source.each { user ->
                log("${user["name"]} — ${user["email"]}")
            }
        }
    }

    flow("create-post") {
        manual()

        call(api.post("/posts") {
            body {
                json {
                    "title"  to "Hello from Integration DSL"
                    "body"   to "This post was created by the integration framework"
                    "userId" to 1
                }
            }
        })

        log("Created post: ${result["id"]}")
    }

    expose {
        rest { port(8080) }

        endpoint(GET, "/users") {
            call(api.get("/users"))
            transform {
                source.each { user ->
                    user["name"]  to target["users"].append { user["email"] to it["email"] }
                }
            }
            respond(200)
        }
    }
}
```

**APIs:**
- JSONPlaceholder: https://jsonplaceholder.typicode.com (free, no auth, fake REST API for testing)
