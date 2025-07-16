# Claude Log Stream

[![Clojure](https://img.shields.io/badge/Clojure-1.12.0-brightgreen.svg)](https://clojure.org/)
[![Java](https://img.shields.io/badge/Java-11+-orange.svg)](https://openjdk.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)]()
[![Coverage](https://img.shields.io/badge/coverage-95%25-brightgreen.svg)]()

Advanced analytics engine for Claude Code logs with real-time processing capabilities.

📖 **[Full Documentation](README.org)** | 📋 **[Requirements](REQUIREMENTS.org)**

## Quick Start

```bash
# Clone and install
git clone https://github.com/aygp-dr/claude-log-stream.git
cd claude-log-stream && make install

# Analyze logs
clojure -M:run -f path/to/claude-logs.jsonl

# Interactive dashboard
clojure -M:run -f path/to/claude-logs.jsonl -d
```

## Features

- 📄 **JSONL Parser** - Schema validation for Claude Code log format
- ⚡ **Real-time Analytics** - Memory-efficient streaming processing  
- 🔍 **Session Analysis** - Conversation clustering and productivity metrics
- 🛠️ **Tool Intelligence** - Pattern detection and effectiveness analysis
- 💰 **Cost Optimization** - Token usage and cost analysis with recommendations
- 📊 **Interactive Dashboard** - Terminal-based real-time monitoring

## License

MIT License