import { useEffect, useState } from "react";
import { api, type UsageDailyRow, type UsageSummaryRow } from "@/lib/api";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { BarChart3 } from "lucide-react";
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { API_TYPE_LABELS, SCENARIO_LABELS } from "@contracts/types";

/** 数据汇聚看板：模型API调用量、用量、耗时、成功率（第四阶段预算修正的数据来源） */
export default function DashboardPage() {
  const [summary, setSummary] = useState<UsageSummaryRow[]>([]);
  const [daily, setDaily] = useState<UsageDailyRow[]>([]);

  useEffect(() => {
    api.usageSummary().then(setSummary).catch(() => setSummary([]));
    api.usageDaily().then(setDaily).catch(() => setDaily([]));
  }, []);

  const totalCalls = summary.reduce((s, r) => s + r.callCount, 0);
  const avgLatency = summary.length
    ? Math.round(summary.reduce((s, r) => s + r.avgLatencyMs, 0) / summary.length)
    : 0;
  const successRate = summary.length
    ? (summary.reduce((s, r) => s + r.successRate * r.callCount, 0) / (totalCalls || 1)) * 100
    : 0;

  return (
    <div className="min-h-screen bg-muted/30 p-6">
      <div className="mx-auto max-w-5xl space-y-6">
        <h1 className="flex items-center gap-2 text-lg font-semibold">
          <BarChart3 className="h-5 w-5 text-primary" /> 预研数据看板
        </h1>

        <div className="grid gap-4 md:grid-cols-3">
          <Card>
            <CardHeader className="pb-2"><CardTitle className="text-sm text-muted-foreground">API调用总量</CardTitle></CardHeader>
            <CardContent><p className="text-3xl font-bold">{totalCalls}</p></CardContent>
          </Card>
          <Card>
            <CardHeader className="pb-2"><CardTitle className="text-sm text-muted-foreground">平均响应耗时</CardTitle></CardHeader>
            <CardContent><p className="text-3xl font-bold">{avgLatency}<span className="text-base font-normal text-muted-foreground"> ms</span></p></CardContent>
          </Card>
          <Card>
            <CardHeader className="pb-2"><CardTitle className="text-sm text-muted-foreground">调用成功率</CardTitle></CardHeader>
            <CardContent><p className="text-3xl font-bold">{successRate.toFixed(1)}<span className="text-base font-normal text-muted-foreground"> %</span></p></CardContent>
          </Card>
        </div>

        <Card>
          <CardHeader><CardTitle className="text-base">每日调用量趋势</CardTitle></CardHeader>
          <CardContent className="h-64">
            {daily.length > 0 ? (
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={daily}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="day" fontSize={12} />
                  <YAxis allowDecimals={false} fontSize={12} />
                  <Tooltip />
                  <Bar dataKey="callCount" name="调用次数" fill="#1d4f91" radius={[4, 4, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            ) : (
              <p className="pt-16 text-center text-sm text-muted-foreground">暂无数据：先在助手或平台演示页执行几次场景调用</p>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader><CardTitle className="text-base">按场景 × 模型类型用量明细</CardTitle></CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>应用场景</TableHead>
                  <TableHead>API类型</TableHead>
                  <TableHead className="text-right">调用次数</TableHead>
                  <TableHead className="text-right">消耗总量</TableHead>
                  <TableHead className="text-right">平均耗时</TableHead>
                  <TableHead className="text-right">成功率</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {(summary).map((r, i) => (
                  <TableRow key={i}>
                    <TableCell><Badge variant="outline">{SCENARIO_LABELS[r.scenario]}</Badge></TableCell>
                    <TableCell>{API_TYPE_LABELS[r.apiType]}</TableCell>
                    <TableCell className="text-right">{r.callCount}</TableCell>
                    <TableCell className="text-right">{r.totalUsage.toFixed(1)} {r.unit}</TableCell>
                    <TableCell className="text-right">{r.avgLatencyMs} ms</TableCell>
                    <TableCell className="text-right">{(r.successRate * 100).toFixed(0)}%</TableCell>
                  </TableRow>
                ))}
                {summary.length === 0 && (
                  <TableRow><TableCell colSpan={6} className="text-center text-sm text-muted-foreground">暂无调用记录</TableCell></TableRow>
                )}
              </TableBody>
            </Table>
            <p className="mt-3 text-xs text-muted-foreground">
              用途：第四阶段将本表实测「单次消耗量」与「触发频次」回填《产品智能AI预研方案及测算表》的蓝色输入格，即完成API年费预算修正。
            </p>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
