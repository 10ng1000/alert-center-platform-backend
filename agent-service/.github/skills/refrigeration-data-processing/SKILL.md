---
name: refrigeration-data-processing
description: "Use when users ask for refrigeration/chiller device data processing, including time-range MySQL query, data analysis, and PDF report generation. 触发关键词: 制冷设备, 冷机, chiller, mysql查询, 时间范围, 数据分析, 报表, PDF."
---

# Refrigeration Data Processing Skill

## Purpose

将制冷设备数据处理任务标准化为固定三步流程:
1. 按用户要求的时间范围查询设备数据（MySQL，当前为 mock）。
2. 基于查询结果做统计分析与风险提示。
3. 生成 PDF 报表（当前为 mock）。

## Required Tool Sequence

必须严格按顺序调用，禁止跳步:
1. `queryRefrigerationDataFromMysql`
2. `analyzeRefrigerationData`
3. `generateRefrigerationPdfReport`

如果用户直接要求“生成报表”，也要先补齐数据查询和分析步骤。

## Input Extraction Rules

从用户请求中提取或补全以下参数:
- `startTime`: ISO-8601 时间，如 `2026-04-02T08:00:00Z`
- `endTime`: ISO-8601 时间，如 `2026-04-02T12:00:00Z`
- `deviceIds`: 逗号分隔设备 ID，可空
- `metrics`: 逗号分隔指标，可空
- `analysisGoal`: 分析目标，如能效优化、异常排查
- `reportTitle`: 报表标题
- `outputFileName`: 报表文件名（可选，建议 `.pdf` 后缀）

缺少关键信息时优先做合理默认，不阻塞流程:
- `deviceIds` 为空: 使用工具默认设备
- `metrics` 为空: 使用工具默认指标
- `analysisGoal` 为空: 使用“制冷系统运行分析”
- `reportTitle` 为空: 使用“制冷设备分析报告”

## Execution Playbook

### Step 1: Query Raw Data

调用 `queryRefrigerationDataFromMysql(startTime, endTime, deviceIds, metrics, intervalMinutes)`。

校验点:
- 查询结果必须 `success=true`
- 必须存在 `rows` 且非空
- 若失败，向用户返回失败原因并停止后续步骤

### Step 2: Analyze Data

调用 `analyzeRefrigerationData(queryResultJson, analysisGoal)`。

校验点:
- 分析结果必须 `success=true`
- 输出中应包含统计结果（min/max/avg）和 `riskHints`
- 若失败，返回错误并停止报表生成

### Step 3: Generate PDF Report

调用 `generateRefrigerationPdfReport(reportTitle, analysisJson, outputFileName)`。

校验点:
- 返回必须 `success=true`
- 必须包含 `filePath`
- 明确告知当前为 mock PDF 生成

## Response Contract

最终对用户回复应包含:
- 执行范围: 时间区间、设备、指标
- 分析摘要: 关键统计与风险提示
- 报表结果: 文件路径 `filePath`
- 实现说明: 当前 MySQL 查询与 PDF 生成为 mock

## Safety and Accuracy

- 禁止虚构“查询成功但无工具返回”的结果。
- 必须基于工具返回数据做分析，不自行编造指标值。
- 当工具失败时，要诚实说明失败原因与下一步建议。

## Example User Requests

- “帮我分析今天上午 8 点到 12 点 chiller-01 和 chiller-02 的运行数据，并生成 PDF 报告。”
- “查询过去 24 小时冷机 COP 和功率趋势，做能效分析后出报表。”
- “制冷设备异常排查，按时间范围查数据并生成一份诊断报告。”
