# Claude Log Stream

Advanced analytics engine for Claude Code logs with real-time processing capabilities.

## Features

- **JSONL Parser**: Comprehensive parsing of Claude Code log format with schema validation
- **Real-time Analytics**: Streaming data processing with memory-efficient algorithms
- **Session Analysis**: Conversation clustering and productivity metrics
- **Tool Usage Intelligence**: Pattern detection and effectiveness analysis
- **Cost Optimization**: Token usage and cost analysis with recommendations
- **Interactive Dashboard**: Terminal-based real-time monitoring
- **CLI Interface**: Powerful command-line tools for log analysis

## Quick Start

### Prerequisites

- Clojure 1.12.0+
- Java 11+
- (Optional) clj-kondo for linting

### Installation

```bash
# Clone and enter directory
cd claude-log-stream

# Install dependencies
make install

# Run tests
make test

# Build and run
make run
```

### Basic Usage

```bash
# Analyze a log file
clojure -M:run -f path/to/claude-logs.jsonl

# Launch interactive dashboard
clojure -M:run -f path/to/claude-logs.jsonl -d

# Watch file for real-time processing
clojure -M:run -f path/to/claude-logs.jsonl -w -v

# Specify output directory
clojure -M:run -f logs.jsonl -o ./analysis-output
```

## Architecture

### Core Components

1. **Parser** (`claude-log-stream.parser`)
   - JSONL parsing with schema validation
   - Support for 4 message types: user, assistant, system, tool-usage
   - Memory-efficient streaming processing

2. **Analyzer** (`claude-log-stream.analyzer`)
   - Session duration and productivity metrics
   - Conversation flow analysis and clustering
   - Tool effectiveness and usage patterns
   - Cost optimization insights

3. **Dashboard** (`claude-log-stream.dashboard`)
   - Real-time terminal interface
   - Multi-panel layout with live updates
   - Interactive controls and navigation

### Message Types

#### User Messages
```json
{
  \"timestamp\": \"2024-01-15T10:30:00Z\",
  \"sessionId\": \"session-123\",
  \"messageId\": \"msg-456\",
  \"conversationId\": \"conv-789\",
  \"content\": \"Help me analyze this data\",
  \"role\": \"user\",
  \"tokenCount\": 5
}
```

#### Assistant Messages
```json
{
  \"timestamp\": \"2024-01-15T10:31:00Z\",
  \"sessionId\": \"session-123\",
  \"messageId\": \"msg-457\",
  \"conversationId\": \"conv-789\",
  \"content\": \"I'll help you analyze the data...\",
  \"role\": \"assistant\",
  \"model\": \"claude-3-opus\",
  \"tokenCount\": 15,
  \"costUsd\": 0.0045
}
```

#### Tool Usage Messages
```json
{
  \"timestamp\": \"2024-01-15T10:31:30Z\",
  \"sessionId\": \"session-123\",
  \"messageId\": \"msg-458\",
  \"conversationId\": \"conv-789\",
  \"toolName\": \"Read\",
  \"toolInput\": {\"filePath\": \"/data/sales.csv\"},
  \"toolOutput\": \"CSV data with 1000 rows...\",
  \"tokenCount\": 250
}
```

## Development

### Running Tests

```bash
# Run all tests
make test

# Run with coverage
make coverage

# Run specific test
clojure -M:test -m kaocha.runner --focus claude-log-stream.parser-test
```

### Code Quality

```bash
# Lint code
make lint

# Full development workflow
make dev
```

### REPL Development

```bash
# Start REPL
make repl

# In REPL:
(require '[claude-log-stream.parser :as parser])
(require '[claude-log-stream.analyzer :as analyzer])

# Parse sample data
(def data (parser/parse-jsonl-file \"test-data/sample.jsonl\"))

# Run analysis
(def analysis (analyzer/analyze-logs data))

# Print summary
(analyzer/print-summary analysis)
```

## Performance

- **Memory Efficiency**: <2GB for 1M messages
- **Processing Speed**: 100K+ messages per minute
- **Concurrent Sessions**: 1000+ sessions supported
- **Real-time Latency**: <500ms for live processing

## Analytics Features

### Session Analysis
- Duration tracking and productivity metrics
- Conversation flow patterns
- Tool usage effectiveness
- Cost optimization recommendations

### Tool Intelligence
- Usage frequency and success rates
- Cross-session pattern detection
- Performance bottleneck identification
- Recommendation engine for optimal tool selection

### Cost Optimization
- Token usage analysis by model
- Cost-per-session tracking
- Expensive operation identification
- Budget optimization suggestions

## Configuration

### Environment Variables

```bash
# Log level (DEBUG, INFO, WARN, ERROR)
export LOG_LEVEL=INFO

# Dashboard refresh rate (milliseconds)
export DASHBOARD_REFRESH_MS=5000

# Memory limits
export JVM_OPTS=\"-Xmx4g -Xms1g\"
```

## Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature-name`
3. Make changes and add tests
4. Run the test suite: `make test`
5. Submit a pull request

### Code Style

- Follow Clojure style guidelines
- Use kebab-case for function and variable names
- Include docstrings for public functions
- Write comprehensive tests for new features

## License

MIT License - see LICENSE file for details.

## Support

For issues and questions:
1. Check the [requirements document](REQUIREMENTS.org) for detailed specifications
2. Review existing tests for usage examples
3. Open an issue for bugs or feature requests