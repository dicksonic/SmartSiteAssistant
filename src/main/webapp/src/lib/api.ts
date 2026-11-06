/**
 * 统一API客户端（后端无关）：前端只依赖这套REST契约，
 * 后端可以是 Java(Spring Boot) 或 Node(演示环境)，返回结构一致。
 * 开发环境通过 vite proxy 转发 /api 到本地 Java 后端（默认 8080）。
 */

const BASE: string = (import.meta.env.VITE_API_BASE as string | undefined) ?? "";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
  });
  const data = (await res.json().catch(() => ({}))) as Record<string, unknown>;
  if (!res.ok) {
    throw new Error((data.error as string) ?? `请求失败(${res.status})`);
  }
  return data as T;
}

const post = <T>(path: string, body: unknown) =>
  request<T>(path, { method: "POST", body: JSON.stringify(body) });
const get = <T>(path: string) => request<T>(path);

// ---- 类型 ----
import type {
  CompareResult,
  HazardResult,
  MaterialReviewResult,
  QaAnswer,
  SessionInfo,
  UsageDailyRow,
  UsageSummaryRow,
} from "@contracts/types";

export type { CompareResult, HazardResult, MaterialReviewResult, SessionInfo, UsageDailyRow, UsageSummaryRow };
export type QaResult = QaAnswer & { question: string; asrText: string | null; ttsText: string };

// ---- 接口 ----
export const api = {
  // 平台接入
  demoSign: (input: { appKey: string; userName: string; orgName?: string }) =>
    post<{ token: string; expiresAt: string }>("/api/integration/demoSign", input),
  sessionInfo: (token: string) =>
    get<SessionInfo>(`/api/integration/sessionInfo?token=${encodeURIComponent(token)}`),

  // 四场景
  ask: (input: { token: string; question?: string; audioBase64?: string }) =>
    post<QaResult>("/api/assistant/ask", input),
  describeHazard: (input: {
    token: string;
    imageBase64?: string;
    text?: string;
    audioBase64?: string;
    location?: string;
  }) => post<HazardResult>("/api/assistant/describeHazard", input),
  reviewMaterial: (input: {
    token: string;
    materialName: string;
    docType: string;
    pages: { pageNo: number; text: string }[];
  }) => post<MaterialReviewResult>("/api/assistant/reviewMaterial", input),
  compareRectification: (input: {
    token: string;
    beforeText: string;
    afterText: string;
    beforeImageBase64?: string;
    afterImageBase64?: string;
  }) => post<CompareResult>("/api/assistant/compareRectification", input),
  synthesize: (input: { token: string; text: string }) =>
    post<{ audioBase64: string }>("/api/assistant/synthesize", input),

  // 看板
  usageSummary: () => get<UsageSummaryRow[]>("/api/stats/usageSummary"),
  usageDaily: () => get<UsageDailyRow[]>("/api/stats/usageDaily"),
};
