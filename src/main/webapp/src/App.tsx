import { Routes, Route } from "react-router";
import Home from "./pages/Home";
import PlatformPage from "./pages/PlatformPage";
import AssistantPage from "./pages/AssistantPage";
import DashboardPage from "./pages/DashboardPage";

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/platform" element={<PlatformPage />} />
      <Route path="/assistant" element={<AssistantPage />} />
      <Route path="/dashboard" element={<DashboardPage />} />
      <Route path="*" element={<Home />} />
    </Routes>
  );
}
