import { useRef, useState } from "react";
import { api, type CompareResult } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Camera, Loader2 } from "lucide-react";

const VERDICT = {
  rectified: { label: "整改到位", cls: "bg-green-100 text-green-800" },
  partial: { label: "部分整改", cls: "bg-amber-100 text-amber-800" },
  not_rectified: { label: "未整改到位", cls: "bg-red-100 text-red-800" },
} as const;

/** 场景④ 文书材料比对：整改前后描述 + 照片比对 */
export function ComparePanel({ token }: { token: string }) {
  const [beforeText, setBeforeText] = useState("");
  const [afterText, setAfterText] = useState("");
  const [beforeImg, setBeforeImg] = useState<string | null>(null);
  const [afterImg, setAfterImg] = useState<string | null>(null);
  const [result, setResult] = useState<CompareResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const beforeRef = useRef<HTMLInputElement>(null);
  const afterRef = useRef<HTMLInputElement>(null);

  const pick = (file: File, set: (b: string | null) => void) => {
    const reader = new FileReader();
    reader.onload = () => set((reader.result as string).split(",")[1]);
    reader.readAsDataURL(file);
  };

  const submit = async () => {
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      setResult(await api.compareRectification({
        token,
        beforeText,
        afterText,
        beforeImageBase64: beforeImg ?? undefined,
        afterImageBase64: afterImg ?? undefined,
      }));
    } catch (e) {
      setError(e instanceof Error ? e.message : "请求失败");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle className="text-base">整改前后材料</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid gap-3 md:grid-cols-2">
            <div className="space-y-2">
              <p className="text-sm font-medium">整改前</p>
              <Textarea placeholder="整改前隐患描述" value={beforeText} onChange={(e) => setBeforeText(e.target.value)} rows={4} />
              <Button variant="outline" size="sm" onClick={() => beforeRef.current?.click()}>
                <Camera className="mr-1 h-4 w-4" /> 整改前照片{beforeImg ? " ✓" : ""}
              </Button>
              <input ref={beforeRef} type="file" accept="image/*" className="hidden" onChange={(e) => e.target.files?.[0] && pick(e.target.files[0], setBeforeImg)} />
            </div>
            <div className="space-y-2">
              <p className="text-sm font-medium">整改后</p>
              <Textarea placeholder="整改后情况描述" value={afterText} onChange={(e) => setAfterText(e.target.value)} rows={4} />
              <Button variant="outline" size="sm" onClick={() => afterRef.current?.click()}>
                <Camera className="mr-1 h-4 w-4" /> 整改后照片{afterImg ? " ✓" : ""}
              </Button>
              <input ref={afterRef} type="file" accept="image/*" className="hidden" onChange={(e) => e.target.files?.[0] && pick(e.target.files[0], setAfterImg)} />
            </div>
          </div>
          <Button onClick={submit} disabled={loading || !beforeText.trim() || !afterText.trim()}>
            {loading && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
            开始比对
          </Button>
          {error && <p className="text-sm text-destructive">{error}</p>}
        </CardContent>
      </Card>

      {result && (
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0">
            <CardTitle className="text-base">比对结论（辅助判断）</CardTitle>
            <span className={`rounded-md px-2 py-1 text-xs font-medium ${VERDICT[result.verdict].cls}`}>
              {VERDICT[result.verdict].label}
            </span>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <div className="flex gap-2">
              <Badge variant={result.textConsistent ? "default" : "secondary"}>
                文书描述{result.textConsistent ? "一致" : "存在差异"}
              </Badge>
              {result.photoSimScore !== null && (
                <Badge variant="secondary">照片相似度 {(result.photoSimScore * 100).toFixed(0)}%</Badge>
              )}
            </div>
            <p className="leading-6">{result.conclusion}</p>
            <p className="text-xs text-muted-foreground">本结论为AI辅助判断，最终复核以人工为准。</p>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
