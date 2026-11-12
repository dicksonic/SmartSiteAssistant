import { useEffect, useRef, useState } from "react";
import { api, type HazardResult, type HazardImageItem, type HazardDraft, type NoticeTemplateItem } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Camera, Loader2, X, ScanSearch, CheckCircle2, FileText, Download, Upload } from "lucide-react";
import { VoiceButton } from "@/components/VoiceButton";

/** 场景② 隐患智能描述：拍照/语音/文字 → 规范描述与整改要求 */
export function HazardPanel({ token }: { token: string }) {
  const [image, setImage] = useState<{ base64: string; preview: string } | null>(null);
  const [text, setText] = useState("");
  const [location, setLocation] = useState("");
  const [result, setResult] = useState<HazardResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const pickImage = (file: File) => {
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = reader.result as string;
      setImage({ base64: dataUrl.split(",")[1], preview: dataUrl });
    };
    reader.readAsDataURL(file);
  };

  const submit = async (audioBase64?: string) => {
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      setResult(await api.describeHazard({
        token,
        imageBase64: image?.base64,
        text: text || undefined,
        audioBase64,
        location: location || undefined,
      }));
    } catch (e) {
      setError(e instanceof Error ? e.message : "请求失败");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="space-y-4">
      <SampleRecognizeSection token={token} />

      <Card>
        <CardHeader>
          <CardTitle className="text-base">隐患采集（拍照 / 语音 / 文字）</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex items-center gap-3">
            <Button variant="outline" onClick={() => fileRef.current?.click()}>
              <Camera className="mr-2 h-4 w-4" /> 拍摄/上传照片
            </Button>
            <input
              ref={fileRef}
              type="file"
              accept="image/*"
              capture="environment"
              className="hidden"
              onChange={(e) => e.target.files?.[0] && pickImage(e.target.files[0])}
            />
            <Input placeholder="隐患部位（如：3号楼5层临边）" value={location} onChange={(e) => setLocation(e.target.value)} className="max-w-xs" />
          </div>
          {image && (
            <div className="relative w-40">
              <img src={image.preview} alt="隐患照片" className="w-40 rounded-md border" />
              <Button variant="destructive" size="icon" className="absolute -right-2 -top-2 h-6 w-6" onClick={() => setImage(null)}>
                <X className="h-3 w-3" />
              </Button>
            </div>
          )}
          <Textarea placeholder="补充口述/文字描述（可选）" value={text} onChange={(e) => setText(e.target.value)} rows={2} />
          <div className="flex gap-2">
            <Button onClick={() => submit()} disabled={loading || (!image && !text.trim())}>
              {loading && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              生成规范描述
            </Button>
            <VoiceButton disabled={loading} onAudio={submit} />
          </div>
          {error && <p className="text-sm text-destructive">{error}</p>}
        </CardContent>
      </Card>

      {result && (
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0">
            <CardTitle className="text-base">隐患记录（自动生成）</CardTitle>
            <Badge>{result.category}</Badge>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <div>
              <p className="font-medium text-muted-foreground">隐患部位</p>
              <p>{result.location}</p>
            </div>
            <div>
              <p className="font-medium text-muted-foreground">规范描述</p>
              <p className="leading-6">{result.description}</p>
            </div>
            <div>
              <p className="font-medium text-muted-foreground">整改要求</p>
              <p className="leading-6">{result.rectification}</p>
            </div>
            <div>
              <p className="font-medium text-muted-foreground">依据</p>
              <Badge variant="secondary">{result.basis}</Badge>
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

/**
 * 隐患图像识别（M2-2）：样本目录选图 → VL 识别 → 可编辑草稿 → 人工确认入库。
 * 草稿字段全部可改是有意的：识别结果本来就预期经人修一遍再确认，
 * 这正是状态机 pending_confirm → confirmed 存在的理由。
 */
function SampleRecognizeSection({ token }: { token: string }) {
  const [images, setImages] = useState<HazardImageItem[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [location, setLocation] = useState("");
  const [recognizing, setRecognizing] = useState(false);
  const [draft, setDraft] = useState<HazardDraft | null>(null);
  // 草稿的可编辑副本：确认时以后端状态机守卫为准，前端这份只是人的修改意见
  const [edit, setEdit] = useState({ category: "", location: "", description: "", rectification: "", basis: "" });
  const [confirming, setConfirming] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [question, setQuestion] = useState("");
  const [asking, setAsking] = useState(false);
  const [qaList, setQaList] = useState<{ q: string; a: string; ms: number; tokens?: number }[]>([]);

  useEffect(() => {
    api.hazardImages(token).then(setImages).catch(() => setImages([]));
  }, [token]);

  const recognize = async () => {
    if (!selected) return;
    setRecognizing(true);
    setError(null);
    setDraft(null);
    setConfirmed(false);
    setQaList([]);
    try {
      const d = await api.recognizeHazard({ token, imageName: selected, location: location || undefined });
      setDraft(d);
      setEdit({
        category: d.category,
        location: d.location,
        description: d.description,
        rectification: d.rectification,
        basis: d.basis,
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : "识别失败");
    } finally {
      setRecognizing(false);
    }
  };

  const confirm = async () => {
    if (!draft) return;
    setConfirming(true);
    setError(null);
    try {
      await api.confirmHazard({
        token,
        hazardId: draft.id,
        category: edit.category,
        location: edit.location,
        description: edit.description,
        rectification: edit.rectification,
        basis: edit.basis,
      });
      setConfirmed(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : "确认失败");
    } finally {
      setConfirming(false);
    }
  };

  const askFollowUp = async (q?: string) => {
        const text = (q ?? question).trim();
        if (!draft || !text) return;
        setAsking(true);
        setError(null);
        try {
          const r = await api.askHazardAgent({ token, hazardId: draft.id, question: text });
          setQaList((prev) => [...prev, { q: text, a: r.answer, ms: r.agentLatencyMs, tokens: r.usage?.totalTokens }]);
          setQuestion("");
        } catch (e) {
          setError(e instanceof Error ? e.message : "追问失败");
        } finally {
          setAsking(false);
        }
    };

  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle className="text-base">隐患图像识别（样本目录 · 本地模拟附件库）</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {images.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              样本目录为空：请在服务端 hazard.image.dir 配置目录中放入现场照片（jpg/png/webp，≤10MB）
            </p>
          ) : (
            <div className="flex flex-wrap gap-3">
              {images.map((img) => (
                <button
                  key={img.name}
                  type="button"
                  onClick={() => setSelected(img.name)}
                  className={`w-28 space-y-1 rounded-md border-2 p-1 text-left ${
                    selected === img.name ? "border-primary" : "border-transparent"
                  }`}
                >
                  <img
                    src={api.hazardImageUrl(token, img.name)}
                    alt={img.name}
                    className="h-20 w-full rounded object-cover"
                  />
                  <p className="truncate text-xs text-muted-foreground" title={img.name}>
                    {img.name}
                  </p>
                </button>
              ))}
            </div>
          )}
          <div className="flex items-center gap-2">
            <Input
              placeholder="隐患部位（可选，人填的优先于模型判断）"
              value={location}
              onChange={(e) => setLocation(e.target.value)}
              className="max-w-xs"
            />
            <Button onClick={recognize} disabled={!selected || recognizing}>
              {recognizing ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <ScanSearch className="mr-2 h-4 w-4" />}
              {recognizing ? "识别中（VL 约 5~30 秒）…" : "识别隐患"}
            </Button>
          </div>
          {error && <p className="text-sm text-destructive">{error}</p>}
        </CardContent>
      </Card>

      {draft && (
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0">
            <CardTitle className="text-base">识别草稿（#{draft.id}，请人工核对修正后确认）</CardTitle>
            <div className="flex gap-2">
              {draft.confidence && <Badge variant="outline">置信度 {draft.confidence}</Badge>}
              {confirmed ? (
                <Badge className="bg-green-600">已确认入库</Badge>
              ) : (
                <Badge variant="secondary">待确认</Badge>
              )}
            </div>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <div className="grid grid-cols-2 gap-3">
              <div>
                <p className="font-medium text-muted-foreground">隐患分类</p>
                <Input value={edit.category} onChange={(e) => setEdit({ ...edit, category: e.target.value })} disabled={confirmed} />
              </div>
              <div>
                <p className="font-medium text-muted-foreground">隐患部位</p>
                <Input value={edit.location} onChange={(e) => setEdit({ ...edit, location: e.target.value })} disabled={confirmed} />
              </div>
            </div>
            <div>
              <p className="font-medium text-muted-foreground">规范描述</p>
              <Textarea value={edit.description} onChange={(e) => setEdit({ ...edit, description: e.target.value })} rows={2} disabled={confirmed} />
            </div>
            <div>
              <p className="font-medium text-muted-foreground">整改要求</p>
              <Textarea value={edit.rectification} onChange={(e) => setEdit({ ...edit, rectification: e.target.value })} rows={2} disabled={confirmed} />
            </div>
            <div>
              <p className="font-medium text-muted-foreground">依据</p>
              <Input value={edit.basis} onChange={(e) => setEdit({ ...edit, basis: e.target.value })} disabled={confirmed} />
            </div>
            <div className="flex items-center gap-3">
              <Button onClick={confirm} disabled={confirming || confirmed}>
                {confirming ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <CheckCircle2 className="mr-2 h-4 w-4" />}
                确认入库
              </Button>
              <p className="text-xs text-muted-foreground">
                耗时 {draft.latencyMs}ms
                {draft.usage && ` · tokens ${draft.usage.totalTokens}`}
              </p>
            </div>
            <div className="space-y-3 border-t pt-3">
                <p className="font-medium text-muted-foreground">就这条隐患追问 Agent（查条文 / 拟通知单 / 查台账）</p>
                {qaList.map((qa, i) => (
                  <div key={i} className="space-y-1 rounded-md bg-muted/50 p-3">
                    <p className="font-medium">Q：{qa.q}</p>
                    <p className="whitespace-pre-wrap leading-6">A：{qa.a}</p>
                    <p className="text-xs text-muted-foreground">
                      {qa.ms}ms{qa.tokens != null && ` · tokens ${qa.tokens}`}
                    </p>
                  </div>
                ))}
                <div className="flex gap-2">
                  <Input
                    placeholder="如：这条隐患违反了哪些条文？"
                    value={question}
                    onChange={(e) => setQuestion(e.target.value)}
                    onKeyDown={(e) => e.key === "Enter" && askFollowUp()}
                  />
                  <Button onClick={() => askFollowUp()} disabled={asking || !question.trim()}>
                    {asking && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
                    追问
                  </Button>
                </div>
                <div className="flex flex-wrap gap-2">
                  <Button variant="outline" size="sm" disabled={asking} onClick={() => askFollowUp("这条隐患违反了哪些条文？请引用条文名称和条款号。")}>
                    查违反条文
                  </Button>
                  <Button variant="outline" size="sm" disabled={asking} onClick={() => askFollowUp("请根据这条隐患记录拟一份整改通知单，包含隐患描述、整改要求、依据条文和整改期限。")}>
                    拟整改通知单
                  </Button>
                </div>
              </div>
            {confirmed && <NoticeSection token={token} hazardId={draft.id} />}
          </CardContent>
        </Card>
      )}
    </>
  );
}

/**
 * 通知单模板化（M2-4）：模板挑选 → LLM 填槽位 → 可编辑表单 → poi-tl 渲染下载。
 * 只在隐患确认入库后出现（后端也只对 confirmed 记录出单）。
 * 槽位值全部可编辑是有意的：编号/日期/单位类槽位 LLM 依 prompt 约束留空，由人补齐。
 */
function NoticeSection({ token, hazardId }: { token: string; hazardId: number }) {
  const [templates, setTemplates] = useState<NoticeTemplateItem[]>([]);
  const [templateName, setTemplateName] = useState<string | null>(null);
  const [slots, setSlots] = useState<string[]>([]);
  const [values, setValues] = useState<Record<string, string>>({});
  const [filling, setFilling] = useState(false);
  const [rendering, setRendering] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [fillInfo, setFillInfo] = useState<{ ms: number; tokens?: number } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const uploadRef = useRef<HTMLInputElement>(null);

  const loadTemplates = () => {
    api.noticeTemplates(token).then((list) => {
      setTemplates(list);
      if (list.length > 0 && !list.some((t) => t.name === templateName)) {
        setTemplateName(list[0].name);
      }
    }).catch(() => setTemplates([]));
  };

  useEffect(loadTemplates, [token]);

  const fill = async () => {
    if (!templateName) return;
    setFilling(true);
    setError(null);
    try {
      const r = await api.fillNotice({ token, hazardId, templateName });
      setSlots(r.slots);
      setValues(r.values);
      setFillInfo({ ms: r.latencyMs, tokens: r.usage?.totalTokens });
    } catch (e) {
      setError(e instanceof Error ? e.message : "填充失败");
    } finally {
      setFilling(false);
    }
  };

  const download = async () => {
    if (!templateName) return;
    setRendering(true);
    setError(null);
    try {
      const blob = await api.renderNotice({ token, templateName, values });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = templateName.replace(/\.docx$/i, "") + "-已填充.docx";
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError(e instanceof Error ? e.message : "渲染失败");
    } finally {
      setRendering(false);
    }
  };

  const upload = async (file: File) => {
    setUploading(true);
    setError(null);
    try {
      await api.uploadNoticeTemplate(token, file);
      loadTemplates();
    } catch (e) {
      setError(e instanceof Error ? e.message : "上传失败");
    } finally {
      setUploading(false);
      if (uploadRef.current) uploadRef.current.value = "";
    }
  };

  return (
    <div className="space-y-3 border-t pt-3">
      <p className="font-medium text-muted-foreground">生成整改通知单（模板填充 · 本地目录模拟平台模板配置）</p>
      {templates.length === 0 ? (
        <p className="text-xs text-muted-foreground">
          模板目录为空：请在服务端 gaios.notice.template-dir 配置目录中放入 .docx 模板（占位符形如 {"{{hazardDesc}}"}）
        </p>
      ) : (
        <div className="space-y-3">
          <div className="flex flex-wrap items-center gap-2">
            <select
              className="h-9 rounded-md border bg-background px-3 text-sm"
              value={templateName ?? ""}
              onChange={(e) => { setTemplateName(e.target.value); setSlots([]); setValues({}); setFillInfo(null); }}
            >
              {templates.map((t) => (
                <option key={t.name} value={t.name}>{t.name}（{t.slots.length} 槽位）</option>
              ))}
            </select>
            <Button onClick={fill} disabled={!templateName || filling}>
              {filling ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <FileText className="mr-2 h-4 w-4" />}
              {filling ? "AI 填充中…" : "AI 填充槽位"}
            </Button>
            <Button variant="outline" onClick={() => uploadRef.current?.click()} disabled={uploading}>
              {uploading ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Upload className="mr-2 h-4 w-4" />}
              上传模板
            </Button>
            <input
              ref={uploadRef}
              type="file"
              accept=".docx"
              className="hidden"
              onChange={(e) => e.target.files?.[0] && upload(e.target.files[0])}
            />
            {fillInfo && (
              <p className="text-xs text-muted-foreground">
                耗时 {fillInfo.ms}ms{fillInfo.tokens != null && ` · tokens ${fillInfo.tokens}`}
              </p>
            )}
          </div>
          {slots.length > 0 && (
            <div className="space-y-2">
              {slots.map((slot) => (
                <div key={slot} className="grid grid-cols-[140px_1fr] items-center gap-2">
                  <p className="truncate text-xs text-muted-foreground" title={slot}>{slot}</p>
                  <Input
                    value={values[slot] ?? ""}
                    onChange={(e) => setValues({ ...values, [slot]: e.target.value })}
                  />
                </div>
              ))}
              <div className="flex items-center gap-3 pt-1">
                <Button onClick={download} disabled={rendering}>
                  {rendering ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Download className="mr-2 h-4 w-4" />}
                  渲染下载 docx
                </Button>
                <p className="text-xs text-muted-foreground">空槽位（编号/日期/单位等）请人工补齐后再下载</p>
              </div>
            </div>
          )}
        </div>
      )}
      {error && <p className="text-sm text-destructive">{error}</p>}
    </div>
  );
}
