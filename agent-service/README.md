# ReACTAgent Backend

An enterprise-oriented AI agent backend built with Spring Boot, Spring AI, DashScope, and Milvus.

This repository focuses on backend engineering and RAG capabilities.

## Overview

ReACTAgent Backend provides Retrieval-Augmented Generation (RAG) chat services with session memory, streaming output, and tool-calling support for operations scenarios.

## Features

- RAG question answering with Milvus vector retrieval
- Query rewrite before retrieval to improve recall quality
- Multi-turn chat with session context management
- Streaming responses via SSE
- Tool integration (docs retrieval, metrics, logs, date-time)
- File upload and automatic vectorization
- **Smart document chunking**: Automatically selects chunking strategy based on file type
  - TXT files: Uses Spring AI Alibaba's TokenTextSplitter for token-based chunking
  - MD files: Uses intelligent heading-based chunking to preserve semantic structure

## Tech Stack

| Component | Version |
|---|---|
| Java | 17 |
| Spring Boot | 3.2.0 |
| Spring AI | Managed by Maven BOM |
| DashScope SDK | 2.17.0 |
| Milvus | 2.6.x |

## Project Structure

```text
src/main/java/org/example/
  agent/tool/           # Agent tools
  controller/           # REST controllers
  service/              # Core business services
  config/               # Spring configurations
src/main/resources/
  application.yml       # Runtime configuration
```

## API Endpoints

- POST /api/chat_stream: streaming chat (SSE)
- POST /api/chat: standard chat
- POST /api/chat/clear: clear session history
- GET /api/chat/session/{sessionId}: get session information
- POST /api/upload: upload documents for indexing
- GET /milvus/health: vector database health check

## Prerequisites

- JDK 17
- Maven 3.8+
- Docker (for Milvus stack)
- DashScope API key

## Configuration

Set the API key before running:

```bash
export DASHSCOPE_API_KEY=your-api-key
```

Main runtime config is in src/main/resources/application.yml.

Example RAG query rewrite config:

```yaml
rag:
  query-rewrite:
    enabled: true
    model: "qwen-plus"
    max-length: 200
```

Document chunking config:

```yaml
document:
  chunk:
    max-size: 800    # Maximum tokens per chunk
    overlap: 100     # Overlap tokens between chunks
```

The system automatically chooses the chunking strategy based on file extension:
- `.txt` files: Spring AI Alibaba TokenTextSplitter (token-based)
- `.md` files: Heading-based semantic chunking (structure-aware)

## Quick Start

1. Start vector database services:

```bash
docker compose -f docker-compose.yml up -d
```

2. Build and run backend:

```bash
mvn clean install
mvn spring-boot:run
```

Then start the backend service.

## Example Requests

```bash
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"demo-session","Question":"What is a vector database?"}'
```

```bash
curl -X POST http://localhost:9900/api/upload \
  -F "file=@document.txt"
```

## Notes

- This repository intentionally excludes frontend static pages and non-engineering materials.
- For local testing UI assets, keep them outside the tracked backend scope.

## License

MIT License. See LICENSE for details.
