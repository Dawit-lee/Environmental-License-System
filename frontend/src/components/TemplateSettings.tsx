import { useEffect, useState } from 'react';
import {
  ANCHOR_LABEL, ANCHORS, getTemplates, updateTemplateItem, type Anchor, type Template, type TemplateItem,
} from '../api/schedule';

// ---------------------------------------------------------------------------
// TemplateSettings — 조직 관리 › 일정 템플릿 (company master): a plain table per template; each row's title, base
// date and offset are edited in place and saved on [저장].
// ---------------------------------------------------------------------------

const input = 'rounded-lg border border-[#E5E3DC] px-2 py-1 text-sm outline-none focus:border-[#1F4E79]';
const head = 'px-3 py-2 font-medium';

export default function TemplateSettings() {
  const [templates, setTemplates] = useState<Template[]>([]);
  const [error, setError] = useState<string | null>(null);
  const load = () => getTemplates().then(setTemplates, (e: Error) => setError(e.message));
  useEffect(() => {
    load();
  }, []);

  return (
    <section className="mt-6 space-y-6">
      <p className="text-sm text-gray-500">프로젝트 › 일정 › [템플릿]으로 시작할 때 쓰는 항목입니다. 오프셋은 기준일로부터의 일수(음수 = 역산) 또는 개월 수입니다.</p>
      {error && <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-[#991B1B]">{error}</p>}
      {templates.map((t) => (
        <div key={t.id}>
          <h2 className="mb-2 text-sm font-semibold text-gray-800">{t.name}</h2>
          <div className="overflow-x-auto rounded-xl border border-[#E5E3DC] bg-white shadow-sm">
            <table className="w-full whitespace-nowrap text-left text-sm">
              <thead className="bg-[#F8F7F2] text-xs text-gray-500">
                <tr>
                  <th className={head}>종류</th><th className={head}>항목</th><th className={head}>기준일</th>
                  <th className={head}>오프셋</th><th className={head}>기간(일)</th><th className={head} />
                </tr>
              </thead>
              <tbody className="divide-y divide-[#E5E3DC]">
                {t.items.map((i) => <Row key={i.id} templateId={t.id} item={i} onError={setError} onSaved={load} />)}
              </tbody>
            </table>
          </div>
        </div>
      ))}
    </section>
  );
}

function Row({ templateId, item, onError, onSaved }: {
  templateId: number; item: TemplateItem; onError: (e: string | null) => void; onSaved: () => void;
}) {
  const [form, setForm] = useState(item);
  const months = form.offsetMonths != null;
  const dirty = JSON.stringify(form) !== JSON.stringify(item);
  const offset = months ? form.offsetMonths : form.offsetDays;
  const setOffset = (v: number | null, m: boolean) => setForm({ ...form, offsetDays: m ? null : v, offsetMonths: m ? v : null });

  return (
    <tr>
      <td className="px-3 py-1.5 text-gray-500">{item.kind === 'TASK' ? '작업' : '마일스톤'}</td>
      <td className="px-3 py-1.5">
        <input aria-label="항목" maxLength={200} className={`${input} w-56`} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
      </td>
      <td className="px-3 py-1.5">
        <select aria-label="기준일" className={input} value={form.anchor} onChange={(e) => setForm({ ...form, anchor: e.target.value as Anchor })}>
          {ANCHORS.map((a) => <option key={a} value={a}>{ANCHOR_LABEL[a]}</option>)}
        </select>
      </td>
      <td className="px-3 py-1.5">
        <input type="number" aria-label="오프셋" className={`${input} w-20`} value={offset ?? ''}
          onChange={(e) => setOffset(e.target.value === '' ? null : Number(e.target.value), months)} />{' '}
        <select aria-label="오프셋 단위" className={input} value={months ? 'month' : 'day'} onChange={(e) => setOffset(offset, e.target.value === 'month')}>
          <option value="day">일</option><option value="month">개월</option>
        </select>
      </td>
      <td className="px-3 py-1.5">
        {item.kind === 'TASK' && (
          <input type="number" min={1} aria-label="기간" className={`${input} w-20`} value={form.durationDays ?? ''}
            onChange={(e) => setForm({ ...form, durationDays: e.target.value === '' ? null : Number(e.target.value) })} />
        )}
      </td>
      <td className="px-3 py-1.5 text-right">
        <button type="button" disabled={!dirty}
          className="rounded-lg border border-[#E5E3DC] px-2.5 py-1 text-xs font-medium text-gray-600 hover:bg-[#F8F7F2] disabled:opacity-30"
          onClick={async () => {
            onError(null);
            try {
              await updateTemplateItem(templateId, item.id, form);
            } catch (e) {
              onError((e as Error).message);
            }
            onSaved();
          }}>저장</button>
      </td>
    </tr>
  );
}
