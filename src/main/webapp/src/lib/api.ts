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
  HazardDraft,
  HazardImageItem,
  HazardAgentAnswer,
  NoticeTemplateItem,
  NoticeFillResult,
} from "@contracts/types";

export type { CompareResult, HazardResult, MaterialReviewResult, SessionInfo, UsageDailyRow, UsageSummaryRow,HazardDraft,HazardImageItem,HazardAgentAnswer,NoticeTemplateItem,NoticeFillResult };
export type QaResult = QaAnswer & { question: string; asrText: string | null; ttsText: string };

export type AgentUsage = { promptTokens: number; completionTokens: number; totalTokens: number };
/** mode=agent：成功；mode=normal-fallback：agent 失败降级（此时用量在 agentUsage） */
export type QaAgentResult = QaResult & {
  mode: "agent" | "normal-fallback";
  degraded: boolean;
  degradeReason?: string;
  agentLatencyMs: number;
  usage?: AgentUsage;
  agentUsage?: AgentUsage;
};

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
  askAgent: (input: { token: string; question: string }) =>
    post<QaAgentResult>("/api/assistant/askAgent", input),
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
  // 隐患图像识别（M2-2）
  hazardImages: (token: string) =>
    get<HazardImageItem[]>(`/api/assistant/hazardImages?token=${encodeURIComponent(token)}`),
  /** 预览图 URL：直接给 <img src> 用，不走 request 封装（返回的是字节流不是 JSON） */
  hazardImageUrl: (token: string, name: string) =>
    `${BASE}/api/assistant/hazardImage?token=${encodeURIComponent(token)}&name=${encodeURIComponent(name)}`,
  recognizeHazard: (input: { token: string; imageName: string; location?: string }) =>
    post<HazardDraft>("/api/assistant/recognizeHazard", input),
  confirmHazard: (input: {
    token: string;
    hazardId: number;
    category: string;
    location: string;
    description: string;
    rectification: string;
    basis: string;
  }) => post<{ id: number; status: string }>("/api/assistant/confirmHazard", input),
  askHazardAgent: (input: { token: string; hazardId: number; question: string }) =>
    post<HazardAgentAnswer>("/api/assistant/askHazardAgent", input),

  // 通知单模板化（M2-4）
  noticeTemplates: (token: string) =>
    get<NoticeTemplateItem[]>(`/api/assistant/noticeTemplates?token=${encodeURIComponent(token)}`),
  fillNotice: (input: { token: string; hazardId: number; templateName: string }) =>
    post<NoticeFillResult>("/api/assistant/fillNotice", input),
  /** 渲染下载：返回 Blob，不走 request 封装（字节流不是 JSON） */
  renderNotice: async (input: { token: string; templateName: string; values: Record<string, string> }) => {
    const res = await fetch(`${BASE}/api/assistant/renderNotice`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(input),
    });
    if (!res.ok) {
      const data = (await res.json().catch(() => ({}))) as Record<string, unknown>;
      throw new Error((data.error as string) ?? `渲染失败(${res.status})`);
    }
    return res.blob();
  },
  /** 办公人员上传模板：multipart，同名覆盖 */
  uploadNoticeTemplate: async (token: string, file: File) => {
    const form = new FormData();
    form.append("token", token);
    form.append("file", file);
    const res = await fetch(`${BASE}/api/assistant/uploadNoticeTemplate`, { method: "POST", body: form });
    const data = (await res.json().catch(() => ({}))) as Record<string, unknown>;
    if (!res.ok) {
      throw new Error((data.error as string) ?? `上传失败(${res.status})`);
    }
    return data as { name: string; sizeKb: number };
  },

  // 看板
  usageSummary: () => get<UsageSummaryRow[]>("/api/stats/usageSummary"),
  usageDaily: () => get<UsageDailyRow[]>("/api/stats/usageDaily"),
};
