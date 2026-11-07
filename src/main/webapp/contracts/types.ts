export * from "./errors";

// ============ 共享业务类型（前后端契约） ============

export type ApiType = "text" | "vision" | "asr" | "tts" | "agent";
export type Scenario = "qa" | "hazard" | "material" | "compare";

export const SCENARIO_LABELS: Record<Scenario, string> = {
  qa: "规范条文问答",
  hazard: "隐患智能描述",
  material: "材料智能预审",
  compare: "文书材料比对",
};

export const API_TYPE_LABELS: Record<ApiType, string> = {
  text: "文本模型",
  vision: "视觉模型",
  asr: "语音识别",
  tts: "语音合成",
  agent: "Agent（G-AIOS）",
};

// ---- 场景① 规范问答 ----
export interface QaAnswer {
  answer: string;
  checklist: string[];
  references: string[];
}

// ---- 场景② 隐患描述 ----
export interface HazardResult {
  category: string;
  location: string;
  description: string;
  rectification: string;
  basis: string;
}

// ---- 场景③ 材料预审 ----
export interface MaterialLogicIssue {
  location: string;
  issue: string;
  suggestion: string;
}
export interface MaterialReviewResult {
  missingItems: string[];
  logicIssues: MaterialLogicIssue[];
  verdict: "pass" | "conditional" | "reject";
}

// ---- 场景④ 文书比对 ----
export interface CompareResult {
  textConsistent: boolean;
  photoSimScore: number | null;
  verdict: "rectified" | "partial" | "not_rectified";
  conclusion: string;
}

// ---- 免登会话 ----
export interface SessionInfo {
  userName: string;
  orgName: string | null;
  platformName: string;
  expiresAt: Date;
}

// ---- 数据看板 ----
export interface UsageSummaryRow {
  apiType: ApiType;
  scenario: Scenario;
  callCount: number;
  totalUsage: number;
  unit: string;
  avgLatencyMs: number;
  successRate: number;
}

export interface UsageDailyRow {
  day: string;
  callCount: number;
  totalUsage: number;
}
