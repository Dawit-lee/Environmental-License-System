import { createRoot } from "react-dom/client";
import PermitApp from "./PermitApp.tsx";
import "./styles/index.css";

// NOTE: the original Figma Make app lives in ./app/App.tsx (auth + Supabase).
// This entry renders the standalone, mock-data-only permit UI instead.
createRoot(document.getElementById("root")!).render(<PermitApp />);
