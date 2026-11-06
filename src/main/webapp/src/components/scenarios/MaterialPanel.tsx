import { useState } from "react";
import { api, type MaterialReviewResult } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Loader2 } from "lucide-react";

const VERDICT = {
  pass: { label: "预审通过", cls: "bg-green-100 text-green-800" },
  conditional: { label: "补正后通过", cls: "bg-amber-100 text-amber-800" },
  reject: { label: "退回补正", cls: "bg-red-100 text-red-800" },
} as const;

/** 场景③ 材料智能预审 */
export function MaterialPanel({ token }: { token: string }) {
  const [name, setName] = useState("");
  const [docType, setDocType] = useState("专项施工方案");
  const [content, setContent] = useState("");
  const [result, setResult] = useState<MaterialReviewResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      // 按空行拆页（预研演示：粘贴文本模拟分页文档；正式版由文档解析服务提供分页文本）
      const pages = content
        .split(/\n\s*\n/)
        .filter((p) => p.trim())
        .map((text, i) => ({ pageNo: i + 1, text: text.trim() }));
      setResult(await api.reviewMaterial({
        token,
        materialName: name,
        docType,
        pages: pages.length ? pages : [{ pageNo: 1, text: content }],
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
          <CardTitle className="text-base">材料上传与解析</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex gap-2">
            <Input placeholder="材料名称（如：深基坑专项施工方案）" value={name} onChange={(e) => setName(e.target.value)} />
            <Select value={docType} onValueChange={setDocType}>
              <SelectTrigger className="w-44"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="专项施工方案">专项施工方案</SelectItem>
                <SelectItem value="验收资料">验收资料</SelectItem>
                <SelectItem value="报审表">报审表</SelectItem>
                <SelectItem value="检测报告">检测报告</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <Textarea
            placeholder="粘贴材料文本内容（空行分页）。演示示例：粘贴若干段方案文本，故意缺少签章页或让前后数据矛盾，验证完整性与逻辑一致性识别。"
            value={content}
            onChange={(e) => setContent(e.target.value)}
            rows={8}
          />
          <Button onClick={submit} disabled={loading || !name.trim() || !content.trim()}>
            {loading && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
            开始预审
          </Button>
          {error && <p className="text-sm text-destructive">{error}</p>}
        </CardContent>
      </Card>

      {result && (
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0">
            <CardTitle className="text-base">预审结论</CardTitle>
            <span className={`rounded-md px-2 py-1 text-xs font-medium ${VERDICT[result.verdict].cls}`}>
              {VERDICT[result.verdict].label}
            </span>
          </CardHeader>
          <CardContent className="space-y-4">
            <div>
              <p className="mb-2 text-sm font-medium">完整性缺项（{result.missingItems.length}）</p>
              {result.missingItems.length === 0 ? (
                <p className="text-sm text-muted-foreground">未发现缺项</p>
              ) : (
                <ul className="list-inside list-disc space-y-1 text-sm">
                  {result.missingItems.map((m, i) => <li key={i}>{m}</li>)}
                </ul>
              )}
            </div>
            <div>
              <p className="mb-2 text-sm font-medium">逻辑一致性问题（{result.logicIssues.length}）</p>
              {result.logicIssues.length === 0 ? (
                <p className="text-sm text-muted-foreground">未发现逻辑问题</p>
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>位置</TableHead>
                      <TableHead>问题</TableHead>
                      <TableHead>建议</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {result.logicIssues.map((it, i) => (
                      <TableRow key={i}>
                        <TableCell><Badge variant="outline">{it.location}</Badge></TableCell>
                        <TableCell className="text-sm">{it.issue}</TableCell>
                        <TableCell className="text-sm text-muted-foreground">{it.suggestion}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
