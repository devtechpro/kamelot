#!/usr/bin/env bash
#
# Products CRUD demo — runs the declarative products-api against its own Postgres.
#
# Spins up a dedicated Postgres container (products-demo-pg) on port 5433.
# Does NOT touch any other databases.
#
# Prerequisites:
#   - Docker (for Postgres)
#   - Java 21+
#
# Usage:
#   ./examples/products/run-demo.sh          # start server + run CRUD tests
#   ./examples/products/run-demo.sh --server # start server only (Ctrl+C to stop)
#   ./examples/products/run-demo.sh --test   # run CRUD tests against already-running server
#
set -euo pipefail

PORT=${PORT:-5500}
PG_PORT=${PG_PORT:-5433}
PG_CONTAINER="products-demo-pg"
PG_USER="postgres"
PG_PASS="postgres"
PG_DB="products"
BASE="http://localhost:$PORT"

# ── Postgres setup ──────────────────────────────────────────────────────────
start_postgres() {
    if docker ps --format '{{.Names}}' | grep -q "^${PG_CONTAINER}$"; then
        echo "  Postgres container '$PG_CONTAINER' already running."
    else
        echo "  Starting Postgres container '$PG_CONTAINER' on port $PG_PORT..."
        docker run -d --name "$PG_CONTAINER" \
            -e POSTGRES_USER="$PG_USER" \
            -e POSTGRES_PASSWORD="$PG_PASS" \
            -e POSTGRES_DB="$PG_DB" \
            -p "$PG_PORT":5432 \
            postgres:16-alpine >/dev/null
        echo "  Waiting for Postgres to accept connections..."
        for i in $(seq 1 30); do
            if docker exec "$PG_CONTAINER" pg_isready -U "$PG_USER" -q 2>/dev/null; then
                break
            fi
            sleep 1
        done
        echo "  Postgres ready."
    fi
}

stop_postgres() {
    if docker ps -a --format '{{.Names}}' | grep -q "^${PG_CONTAINER}$"; then
        echo "  Stopping Postgres container..."
        docker rm -f "$PG_CONTAINER" >/dev/null 2>&1
    fi
}

# ── Server ──────────────────────────────────────────────────────────────────
start_server() {
    echo "  Starting products-api on port $PORT..."
    POSTGRES_URL="jdbc:postgresql://localhost:$PG_PORT/$PG_DB" \
    POSTGRES_USER="$PG_USER" \
    POSTGRES_PASSWORD="$PG_PASS" \
    PORT="$PORT" \
    ./gradlew :examples:products:run "$@"
}

start_server_background() {
    echo "  Starting products-api on port $PORT (background)..."
    POSTGRES_URL="jdbc:postgresql://localhost:$PG_PORT/$PG_DB" \
    POSTGRES_USER="$PG_USER" \
    POSTGRES_PASSWORD="$PG_PASS" \
    PORT="$PORT" \
    ./gradlew :examples:products:run &
    SERVER_PID=$!

    echo "  Waiting for server to be ready..."
    for i in $(seq 1 60); do
        if curl -sf "$BASE/products" >/dev/null 2>&1; then
            echo "  Server ready (PID $SERVER_PID)."
            return 0
        fi
        sleep 1
    done
    echo "  ERROR: Server did not start in time."
    kill $SERVER_PID 2>/dev/null
    return 1
}

# ── CRUD tests ──────────────────────────────────────────────────────────────
run_tests() {
    echo ""
    echo "══════════════════════════════════════════════════════════════"
    echo "  Products CRUD Demo — declarative flows + Postgres"
    echo "══════════════════════════════════════════════════════════════"

    echo ""
    echo "── POST /products (create 3 products) ──"
    echo "   Each gets: slug (from name), created_at, updated_at (from process step)"
    echo ""
    curl -s -X POST "$BASE/products" -H 'Content-Type: application/json' \
        -d '{"name":"Widget Pro","price":9.99,"category":"tools","sku":"WDG-001"}' | python3 -m json.tool
    echo ""
    curl -s -X POST "$BASE/products" -H 'Content-Type: application/json' \
        -d '{"name":"Super Gadget 3000","price":24.50,"category":"electronics","sku":"GDG-002"}' | python3 -m json.tool
    echo ""
    curl -s -X POST "$BASE/products" -H 'Content-Type: application/json' \
        -d '{"name":"Sprocket","price":4.75,"category":"tools","sku":"SPR-003"}' | python3 -m json.tool

    echo ""
    echo "── GET /products (list all) ──"
    curl -s "$BASE/products" | python3 -m json.tool

    echo ""
    echo "── GET /products/2 (get single) ──"
    curl -s "$BASE/products/2" | python3 -m json.tool

    echo ""
    echo "── GET /products?search=gadget (search) ──"
    curl -s "$BASE/products?search=gadget" | python3 -m json.tool

    echo ""
    echo "── PUT /products/1 (update — adds updated_at) ──"
    curl -s -X PUT "$BASE/products/1" -H 'Content-Type: application/json' \
        -d '{"name":"Widget Pro Max","price":14.99,"category":"premium tools","sku":"WDG-001"}' | python3 -m json.tool

    echo ""
    echo "── DELETE /products/3 (delete Sprocket) ──"
    curl -s -X DELETE "$BASE/products/3" | python3 -m json.tool

    echo ""
    echo "── GET /products (final state via API) ──"
    curl -s "$BASE/products" | python3 -m json.tool

    echo ""
    echo "── Direct Postgres query (proof data is in the database) ──"
    docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -c \
        "SELECT id, name, price, slug, category, created_at, updated_at FROM products ORDER BY id;"

    echo ""
    echo "── Table schema (generated from spec) ──"
    docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -c "\d products"

    echo ""
    echo "══════════════════════════════════════════════════════════════"
    echo "  Demo complete. Container '$PG_CONTAINER' will be cleaned up."
    echo "══════════════════════════════════════════════════════════════"
}

# ── Main ────────────────────────────────────────────────────────────────────
cleanup() {
    if [[ -n "${SERVER_PID:-}" ]]; then
        kill $SERVER_PID 2>/dev/null || true
        wait $SERVER_PID 2>/dev/null || true
    fi
    stop_postgres
}

case "${1:-}" in
    --server)
        start_postgres
        trap 'stop_postgres' EXIT
        start_server
        ;;
    --test)
        run_tests
        ;;
    *)
        trap cleanup EXIT
        start_postgres
        start_server_background
        run_tests
        ;;
esac
