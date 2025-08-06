# Project configuration
PROJECT_NAME := claude-log-stream
PROJECT_ROOT := $(shell pwd)
EMACS_CONFIG := $(PROJECT_NAME).el
TMUX_SESSION := $(PROJECT_NAME)

.PHONY: help test coverage lint clean install run repl uberjar docker analyze-sample analyze-project analyze-claude-logs find-claude-logs demo analyze-project-verbose analyze-recent emacs-init tmux-dev tmux-stop

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
	@echo "  tmux-dev        - Start tmux session with Clojure-configured Emacs"
	@echo "  tmux-stop       - Stop the project tmux session"
	@echo "  emacs-init      - Create Emacs configuration for Clojure development"

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

# Create Emacs configuration for Clojure development
emacs-init: $(EMACS_CONFIG)

$(EMACS_CONFIG):
	@echo "Creating Emacs configuration for $(PROJECT_NAME)..."
	@echo ";; Emacs configuration for $(PROJECT_NAME)" > $(EMACS_CONFIG)
	@echo ";; Generated on $$(date)" >> $(EMACS_CONFIG)
	@cat >> $(EMACS_CONFIG) <<'EOF'
	
	;; Package initialization
	(require 'package)
	(add-to-list 'package-archives '("melpa" . "https://melpa.org/packages/") t)
	(package-initialize)
	
	;; Install required packages if not present
	(defvar clojure-packages
	  '(cider clojure-mode paredit rainbow-delimiters company))
	
	(dolist (pkg clojure-packages)
	  (unless (package-installed-p pkg)
	    (package-refresh-contents)
	    (package-install pkg)))
	
	;; Enable paredit for Clojure
	(add-hook 'clojure-mode-hook #'paredit-mode)
	(add-hook 'cider-repl-mode-hook #'paredit-mode)
	
	;; Enable rainbow delimiters
	(add-hook 'prog-mode-hook #'rainbow-delimiters-mode)
	
	;; Company mode for completions
	(global-company-mode)
	
	;; CIDER configuration
	(setq cider-repl-display-help-banner nil)
	(setq cider-repl-pop-to-buffer-on-connect 'display-only)
	(setq cider-save-file-on-load t)
	(setq cider-font-lock-dynamically '(macro core function var))
	
	;; Project-specific settings
	(setq default-directory "$(PROJECT_ROOT)")
	(setq cider-clojure-cli-global-options "-A:dev:test")
	
	;; Org-mode babel support for Clojure
	(require 'ob-clojure)
	(setq org-babel-clojure-backend 'cider)
	
	;; TRAMP configuration for remote development
	(require 'tramp)
	(setq tramp-default-method "ssh")
	
	;; Start CIDER REPL automatically
	(add-hook 'clojure-mode-hook
	          (lambda ()
	            (when (and buffer-file-name
	                       (string-match-p "$(PROJECT_ROOT)" buffer-file-name))
	              (unless (cider-connected-p)
	                (cider-jack-in-clj)))))
	
	;; Open project main file
	(find-file "$(PROJECT_ROOT)/src/claude_log_stream/core.clj")
	
	(message "Claude Log Stream project loaded. Use C-c C-k to load buffer, C-c C-e to eval expression.")
	EOF
	@echo "Emacs configuration created: $(EMACS_CONFIG)"

# Start tmux session with Clojure-configured Emacs
tmux-dev: emacs-init
	@if tmux has-session -t $(TMUX_SESSION) 2>/dev/null; then \
		echo "Tmux session '$(TMUX_SESSION)' already exists. Attaching..."; \
		tmux attach-session -t $(TMUX_SESSION); \
	else \
		echo "Starting new tmux session '$(TMUX_SESSION)' with Emacs..."; \
		tmux new-session -d -s $(TMUX_SESSION) "emacs -nw -Q -l $(EMACS_CONFIG)"; \
		echo "Session started. TTY: $$(tmux list-panes -t $(TMUX_SESSION) -F '#{pane_tty}')"; \
		echo "Attach with: tmux attach -t $(TMUX_SESSION)"; \
	fi

# Stop tmux session
tmux-stop:
	@if tmux has-session -t $(TMUX_SESSION) 2>/dev/null; then \
		echo "Stopping tmux session '$(TMUX_SESSION)'..."; \
		tmux kill-session -t $(TMUX_SESSION); \
		echo "Session stopped."; \
	else \
		echo "No tmux session '$(TMUX_SESSION)' found."; \
	fi