#!/usr/bin/env bash
# check-dependency-updates.sh - Check for dependency updates across all services
# Usage: ./scripts/check-dependency-updates.sh [service-name]
# If no service specified, checks all services

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICES_DIR="${PROJECT_ROOT}/services"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Dependency Update Checker${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Check if specific service requested
SPECIFIC_SERVICE="${1:-}"

check_java_service() {
    local service=$1
    local service_path="${SERVICES_DIR}/${service}"

    echo -e "${YELLOW}📦 Checking ${service} (Maven)...${NC}"

    if [[ ! -f "${service_path}/pom.xml" ]]; then
        echo -e "${RED}  ❌ pom.xml not found${NC}"
        return
    fi

    cd "${service_path}"

    if [[ -f "mvnw" ]]; then
        echo -e "${GREEN}  Checking dependency updates...${NC}"
        ./mvnw -q versions:display-dependency-updates

        echo -e "${GREEN}  Checking plugin updates...${NC}"
        ./mvnw -q versions:display-plugin-updates

        echo -e "${GREEN}  Checking property updates...${NC}"
        ./mvnw -q versions:display-property-updates
    else
        echo -e "${YELLOW}  ⚠️  Maven wrapper not found, skipping${NC}"
    fi

    echo ""
}

check_go_service() {
    local service=$1
    local service_path="${SERVICES_DIR}/${service}"

    echo -e "${YELLOW}📦 Checking ${service} (Go)...${NC}"

    if [[ ! -f "${service_path}/go.mod" ]]; then
        echo -e "${RED}  ❌ go.mod not found${NC}"
        return
    fi

    cd "${service_path}"

    if command -v go &> /dev/null; then
        echo -e "${GREEN}  Checking for updates...${NC}"
        go list -u -m all 2>/dev/null | grep '\[' || echo -e "${GREEN}  ✓ All dependencies up to date${NC}"
    else
        echo -e "${RED}  ❌ Go not installed${NC}"
    fi

    echo ""
}

check_rust_service() {
    local service=$1
    local service_path="${SERVICES_DIR}/${service}"

    echo -e "${YELLOW}📦 Checking ${service} (Rust)...${NC}"

    if [[ ! -f "${service_path}/Cargo.toml" ]]; then
        echo -e "${RED}  ❌ Cargo.toml not found${NC}"
        return
    fi

    cd "${service_path}"

    if command -v cargo &> /dev/null; then
        if command -v cargo-outdated &> /dev/null; then
            echo -e "${GREEN}  Checking for updates...${NC}"
            cargo outdated
        else
            echo -e "${YELLOW}  ⚠️  cargo-outdated not installed${NC}"
            echo -e "${YELLOW}  Install with: cargo install cargo-outdated${NC}"
        fi
    else
        echo -e "${RED}  ❌ Cargo not installed${NC}"
    fi

    echo ""
}

check_python_service() {
    local service=$1
    local service_path="${SERVICES_DIR}/${service}"

    echo -e "${YELLOW}📦 Checking ${service} (Python)...${NC}"

    if [[ ! -f "${service_path}/pyproject.toml" ]]; then
        echo -e "${RED}  ❌ pyproject.toml not found${NC}"
        return
    fi

    cd "${service_path}"

    if command -v uv &> /dev/null; then
        echo -e "${GREEN}  Syncing environment...${NC}"
        uv sync --frozen --all-groups --quiet

        echo -e "${GREEN}  Outdated packages:${NC}"
        uv pip list --outdated || echo -e "${GREEN}  ✓ All dependencies up to date${NC}"
    else
        echo -e "${RED}  ❌ uv not installed (install: brew install uv)${NC}"
    fi

    echo ""
}

check_node_service() {
    local service=$1
    local service_path="${SERVICES_DIR}/${service}"

    echo -e "${YELLOW}📦 Checking ${service} (Node.js)...${NC}"

    if [[ ! -f "${service_path}/package.json" ]]; then
        echo -e "${RED}  ❌ package.json not found${NC}"
        return
    fi

    cd "${service_path}"

    if command -v npm &> /dev/null; then
        echo -e "${GREEN}  Checking for updates...${NC}"
        npm outdated || echo -e "${GREEN}  ✓ All dependencies up to date${NC}"
    else
        echo -e "${RED}  ❌ npm not installed${NC}"
    fi

    echo ""
}

# Main execution
if [[ -n "${SPECIFIC_SERVICE}" ]]; then
    # Check specific service
    echo -e "${BLUE}Checking specific service: ${SPECIFIC_SERVICE}${NC}"
    echo ""

    case "${SPECIFIC_SERVICE}" in
        quarkus-receiver|quarkus-sinker|spring-receiver|spring-virtual-receiver)
            check_java_service "${SPECIFIC_SERVICE}"
            ;;
        go-receiver)
            check_go_service "${SPECIFIC_SERVICE}"
            ;;
        rust-receiver)
            check_rust_service "${SPECIFIC_SERVICE}"
            ;;
        python-receiver)
            check_python_service "${SPECIFIC_SERVICE}"
            ;;
        node-receiver)
            check_node_service "${SPECIFIC_SERVICE}"
            ;;
        *)
            echo -e "${RED}Unknown service: ${SPECIFIC_SERVICE}${NC}"
            echo "Available services: quarkus-receiver, quarkus-sinker, spring-receiver, spring-virtual-receiver, go-receiver, rust-receiver, python-receiver, node-receiver"
            exit 1
            ;;
    esac
else
    # Check all services
    echo -e "${BLUE}Checking all services...${NC}"
    echo ""

    # Java services
    check_java_service "quarkus-receiver"
    check_java_service "quarkus-sinker"
    check_java_service "spring-receiver"
    check_java_service "spring-virtual-receiver"

    # Go service
    check_go_service "go-receiver"

    # Rust service
    check_rust_service "rust-receiver"

    # Python service
    check_python_service "python-receiver"

    # Node.js service
    check_node_service "node-receiver"
fi

echo -e "${BLUE}========================================${NC}"
echo -e "${GREEN}✓ Dependency check complete!${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo -e "${YELLOW}Next steps:${NC}"
echo "  1. Review the updates above"
echo "  2. Update dependencies following docs/DEPENDENCY_UPDATE_GUIDE.md"
echo "  3. Run benchmarks: scripts/run-benchmark-matrix.sh"
echo "  4. Document any performance changes in docs/BENCHMARK_HISTORY.md"




