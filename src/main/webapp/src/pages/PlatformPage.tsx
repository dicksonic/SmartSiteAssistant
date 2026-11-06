import { useState } from "react";
import { api } from "@/lib/api";
import { Sheet, SheetContent, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import {
  Building2, ClipboardCheck, FolderKanban, LayoutDashboard, Sparkles, Loader2,
} from "lucide-react";

/**
 * 模拟已建B/S监管平台：演示"方案2"接入——
 * 平台仅添加一个图标入口，点击后在当前页弹层（抽屉）中内嵌iframe打开AI助手，无跳转割裂。
 */
export default function PlatformPage() {
  const [open, setOpen] = useState(false);
  const [token, setToken] = useState<string | null>(null);
  const [signing, setSigning] = useState(false);

  const openAssistant = async () => {
    if (token) {
      setOpen(true);
      return;
    }
    // 生产环境：由平台后端签名后换取Token；演示环境：demoSign模拟平台侧签名
    setSigning(true);
    try {
      const d = await api.demoSign({ appKey: "demo-platform", userName: "张工", orgName: "市建设工程质量安全监督站" });
      setToken(d.token);
      setOpen(true);
    } finally {
      setSigning(false);
    }
  };

  const menus = [
    { icon: LayoutDashboard, label: "工作台", active: false },
    { icon: FolderKanban, label: "项目管理", active: true },
    { icon: ClipboardCheck, label: "监督检查", active: false },
    { icon: Building2, label: "企业信息", active: false },
  ];

  const projects = [
    ["PRJ-2025-041", "城东安置房二期", "主体施工", "正常"],
    ["PRJ-2025-038", "滨河路市政桥梁", "基础施工", "预警"],
    ["PRJ-2025-035", "高新区厂房项目", "装饰装修", "正常"],
    ["PRJ-2025-031", "老城改造3号地块", "基坑施工", "整改中"],
  ];

  return (
    <div className="flex min-h-screen flex-col bg-muted/30">
      {/* 平台顶栏 */}
      <header className="flex items-center justify-between bg-[#1d4f91] px-6 py-3 text-white">
        <div className="flex items-center gap-3">
          <Building2 className="h-6 w-6" />
          <span className="text-lg font-semibold">建设工程质量安全监管平台</span>
          <Badge variant="secondary" className="bg-white/20 text-white">演示环境</Badge>
        </div>
        <div className="flex items-center gap-3">
          {/* AI助手图标入口：平台侧仅需添加此图标与点击事件 */}
          <Button variant="secondary" size="sm" onClick={openAssistant} disabled={signing} className="gap-1">
            {signing ? <Loader2 className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />}
            AI助手
          </Button>
          <span className="text-sm">张工（监督站）</span>
        </div>
      </header>

      <div className="flex flex-1">
        {/* 平台侧边菜单 */}
        <aside className="w-48 border-r bg-background py-4">
          {menus.map((m) => (
            <div
              key={m.label}
              className={`flex cursor-pointer items-center gap-2 px-5 py-2.5 text-sm ${
                m.active ? "border-r-2 border-primary bg-muted font-medium text-primary" : "text-muted-foreground hover:bg-muted/50"
              }`}
            >
              <m.icon className="h-4 w-4" /> {m.label}
            </div>
          ))}
          {/* 侧边菜单同样可放入口，此处演示顶栏图标 */}
        </aside>

        {/* 平台业务内容（模拟） */}
        <main className="flex-1 p-6">
          <h2 className="mb-4 text-base font-semibold">在建项目列表</h2>
          <div className="rounded-md border bg-background">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>项目编号</TableHead>
                  <TableHead>项目名称</TableHead>
                  <TableHead>施工阶段</TableHead>
                  <TableHead>状态</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {projects.map(([no, name, stage, status]) => (
                  <TableRow key={no}>
                    <TableCell className="font-mono text-xs">{no}</TableCell>
                    <TableCell>{name}</TableCell>
                    <TableCell>{stage}</TableCell>
                    <TableCell>
                      <Badge variant={status === "正常" ? "secondary" : status === "预警" ? "destructive" : "outline"}>{status}</Badge>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <p className="mt-4 text-xs text-muted-foreground">
            接入说明：本平台为模拟已建B/S平台。点击右上角「AI助手」图标，即在当前页弹层中免登打开助手——无需跳转新页面，平台侧仅需一次签名换Token。
          </p>
        </main>
      </div>

      {/* 弹层iframe：方案2接入形态 */}
      <Sheet open={open} onOpenChange={setOpen}>
        <SheetContent side="right" className="w-full p-0 sm:max-w-2xl">
          <SheetHeader className="border-b px-4 py-3">
            <SheetTitle className="flex items-center gap-2 text-base">
              <Sparkles className="h-4 w-4 text-primary" /> 监管AI助手
            </SheetTitle>
          </SheetHeader>
          {open && token && (
            <iframe
              src={`/assistant?token=${token}&embed=1`}
              title="监管AI助手"
              className="h-[calc(100vh-57px)] w-full border-0"
            />
          )}
        </SheetContent>
      </Sheet>
    </div>
  );
}
