.PHONY: help test coverage lint clean install run repl uberjar docker analyze-sample analyze-project analyze-claude-logs find-claude-logs demo analyze-project-verbose analyze-recent

# Default target
help:
	@echo "Claude Log Stream - Available targets:"
	@echo "  install         - Install dependencies"
	@echo "  test            - Run unit tests"
	@echo "  coverage        - Run tests with coverage report"
	@echo "  lint            - Run code linting (clj-kondo)"
	@echo "  clean           - Clean build artifacts"
	@echo "  run             - Run the application"
	@echo "  repl            - Start REPL"
	@echo "  uberjar         - Build standalone JAR"
	@echo "  docker          - Build Docker image"
	@echo "  demo            - Run simple demo"
	@echo "  analyze-sample  - Analyze included sample data"
	@echo "  analyze-project - Analyze current project Claude logs"
	@echo "  find-claude-logs - Find Claude log files in system"
	@echo "  analyze-claude-logs - Analyze system Claude logs with dashboard"
	@echo "  analyze-project-verbose - Detailed analysis of current project logs"
	@echo "  analyze-recent      - Analyze most recent Claude session"

# Install dependencies
install:
	clojure -P -X:test:dev

# Run tests
test:
	clojure -M:test -m kaocha.runner

# Run tests with coverage
coverage:
	clojure -M:test -m kaocha.runner --plugin cloverage

# Lint code (requires clj-kondo)
lint:
	@command -v clj-kondo >/dev/null 2>&1 || (echo \"clj-kondo not found. Install with: brew install clj-kondo\" && exit 1)
	clj-kondo --lint src test

# Clean build artifacts
clean:
	rm -rf target/
	rm -rf .cpcache/
	rm -rf coverage/

# Run the application
run:
	clojure -M:run

# Start REPL
repl:
	clojure -M:dev:repl

# Build standalone JAR
uberjar:
	clojure -X:uberjar

# Build Docker image
docker:
	docker build -t claude-log-stream .

# Development workflow - install, lint, test
dev: install lint test

# CI workflow - install, lint, test with coverage
ci: install lint coverage

# Demo and analysis targets
demo:
	clojure -M demo.clj

# Analyze included sample data
analyze-sample:
	clojure -M:run -f test/resources/sample.jsonl

# Analyze sample data with dashboard
analyze-sample-dashboard:
	clojure -M:run -f test/resources/sample.jsonl -d

# Find Claude log files in the system
find-claude-logs:
	@echo "Searching for Claude log files..."
	@find /home/jwalsh -name "*.jsonl" -path "*claude*" 2>/dev/null || true
	@find /home/jwalsh -name "claude*.log" 2>/dev/null || true
	@find /home/jwalsh/.claude -name "*.jsonl" 2>/dev/null || true
	@find /home/jwalsh/.anthropic -name "*.jsonl" 2>/dev/null || true
	@ls -la /home/jwalsh/.claude/ 2>/dev/null || echo "No .claude directory found"

# Analyze project-specific Claude logs  
analyze-project:
	@echo "Looking for Claude logs for current project..."
	@PROJECT_DIR=$$(pwd | tr '/' '-'); \
	CLAUDE_LOG_DIR="/home/jwalsh/.claude/projects/$${PROJECT_DIR}"; \
	echo "Checking directory: $$CLAUDE_LOG_DIR"; \
	if [ -d "$$CLAUDE_LOG_DIR" ]; then \
		LATEST_LOG=$$(ls -t "$$CLAUDE_LOG_DIR"/*.jsonl 2>/dev/null | head -1); \
		if [ -n "$$LATEST_LOG" ]; then \
			echo "Found project Claude log: $$LATEST_LOG"; \
			clojure -M analyze_real_logs.clj "$$LATEST_LOG"; \
		else \
			echo "No .jsonl files found in $$CLAUDE_LOG_DIR"; \
			echo "Falling back to sample data..."; \
			clojure -M simple_test.clj test/resources/sample.jsonl; \
		fi; \
	else \
		echo "No Claude logs found for project at $$CLAUDE_LOG_DIR"; \
		echo "Falling back to sample data..."; \
		clojure -M simple_test.clj test/resources/sample.jsonl; \
	fi

# Analyze system Claude logs with dashboard
analyze-claude-logs:
	@echo "Searching for Claude logs with dashboard..."
	@CLAUDE_LOG=$$(find /home/jwalsh -name "*.jsonl" -path "*claude*" 2>/dev/null | head -1); \
	if [ -n "$$CLAUDE_LOG" ]; then \
		echo "Found Claude log: $$CLAUDE_LOG"; \
		echo "Starting dashboard analysis..."; \
		clojure -M:run -f "$$CLAUDE_LOG" -d -v; \
	else \
		echo "No Claude logs found. Using sample data with dashboard..."; \
		clojure -M:run -f test/resources/sample.jsonl -d; \
	fi

# Detailed analysis of current project logs
analyze-project-verbose:
	@echo "=== Detailed Project Analysis ==="
	@PROJECT_DIR=$$(pwd | tr '/' '-'); \
	CLAUDE_LOG_DIR="/home/jwalsh/.claude/projects/$${PROJECT_DIR}"; \
	if [ -d "$$CLAUDE_LOG_DIR" ]; then \
		echo "Project: $$(basename $$(pwd))"; \
		echo "Claude logs directory: $$CLAUDE_LOG_DIR"; \
		echo ""; \
		echo "Available log files:"; \
		ls -lah "$$CLAUDE_LOG_DIR"/*.jsonl 2>/dev/null || echo "No log files found"; \
		echo ""; \
		LATEST_LOG=$$(ls -t "$$CLAUDE_LOG_DIR"/*.jsonl 2>/dev/null | head -1); \
		if [ -n "$$LATEST_LOG" ]; then \
			echo "=== Analysis of Latest Session ==="; \
			clojure -M analyze_real_logs.clj "$$LATEST_LOG"; \
			echo ""; \
			echo "=== Session Statistics ==="; \
			echo "Log file: $$LATEST_LOG"; \
			echo "File size: $$(wc -c < "$$LATEST_LOG") bytes"; \
			echo "Total lines: $$(wc -l < "$$LATEST_LOG")"; \
		fi; \
	else \
		echo "No Claude logs found for this project"; \
	fi

# Analyze most recent Claude session across all projects
analyze-recent:
	@echo "Finding most recent Claude session..."
	@LATEST_LOG=$$(find /home/jwalsh/.claude/projects -name "*.jsonl" -exec ls -t {} + 2>/dev/null | head -1); \
	if [ -n "$$LATEST_LOG" ]; then \
		echo "Most recent session: $$LATEST_LOG"; \
		clojure -M analyze_real_logs.clj "$$LATEST_LOG"; \
	else \
		echo "No Claude log files found"; \
	fi