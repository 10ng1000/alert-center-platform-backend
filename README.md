# Alert Center Platform

A lightweight microservice platform for refrigeration device alarms and work order handling.

## Overview

This project uses Spring Cloud Alibaba and event-driven services to process alarms end-to-end:

1. Alarm is triggered through the gateway API.
2. `alarm-service` aggregates/escalates alarm severity and publishes an event.
3. `workorder-service` consumes the event, creates a work order, and publishes a notification event.
4. `notification-service` consumes notification events and performs mock delivery.

## Modules

- `gateway-service`: API gateway, routing, JWT authentication
- `alarm-service`: Alarm ingestion, deduplication, escalation, event publishing
- `workorder-service`: Work order creation, idempotent consumption, retry handling
- `notification-service`: Notification event consumer (mock)
- `device-profile-service`: Device profile provider (Dubbo)
- `admin-service`: Admin registration/login and device-assignee binding
- `common-api`: Shared DTOs, constants, RPC interfaces

## Tech Stack

- Java 17
- Maven 3.8+
- Spring Cloud Alibaba + Nacos
- Apache Dubbo
- RocketMQ
- MySQL + Redis
- Docker Compose

## Quick Start

From project root:

```bash
./scripts/start-all.sh
```

This script starts dependencies, initializes database schema, builds modules, starts services, and performs basic health checks.

Stop everything:

```bash
./scripts/stop-all.sh
```

View logs:

```bash
./scripts/logs.sh
```

## Verification Pages

- `http://127.0.0.1:8080/verify.html`
- `http://127.0.0.1:8080/admin.html`

## API Notes

- Alarm trigger: `POST /api/alarm/alarm/trigger`
- Work order list: `GET /api/workorder/workorder/list`
- Complete work order: `POST /api/workorder/workorder/{id}/complete`

## JWT

Default gateway JWT secret:

`ChangeThisSecretToAtLeast32Bytes!!`

Use header:

`Authorization: Bearer <token>`

## Backup

The original README is backed up as `README.backup-20260418.md`.
