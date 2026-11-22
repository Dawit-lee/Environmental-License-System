import { useEffect, useState } from 'react';
import {
  ANCHOR_LABEL, ANCHORS, createTemplateItem, deleteTemplateItem, getDisciplines, getTemplates, updateTemplateItem,
  type Anchor, type Discipline, type ItemKind, type Template, type TemplateItem,
} from '../api/schedule';

// ---------------------------------------------------------------------------
// TemplateSettings — 조직 관리 › 일정 템플릿 (company master): a plain table per template; each row's title, base
// date and offset are edited in place and saved on [저장], [삭제] removes it, and the last row adds one.
// ---------------------------------------------------------------------------

const input = 'rounded border border-line px-2 py-1 text-sm outline-none focus:border-brand';
const head = 'px-3 py-2 font-medium';

export default function TemplateSettings() {
  const [templates, setTemplates] = useState<Template[]>([]);
  const [disciplines, setDisciplines] = useState<Discipline[]>([]);
  const [error, setError] = useState<string | null>(null);
  const load = () => getTemplates().then(setTemplates, (e: Error) => setError(e.message));
  useEffect(() => {
    load();
    getDisciplines().then((ds) => setDisciplines(ds.filter((d) => d.code && d.active)), () => {});
  }, []);

  return (
    <section className="mt-6 space-y-6">
      <p className="text-sm text-mute">프로젝트 › 일정 › [템플릿]으로 시작할 때 쓰는 항목입니다. 오프셋은 기준일로부터의 일수(음수 = 역산) 또는 개월 수입니다.</p>
      {error && <p role="alert" className="rounded bg-red-50 px-3 py-2 text-sm text-danger">{error}</p>}
      {templates.map((t) => (
        <div key={t.id}>
          <h2 className="mb-2 text-sm font-semibold text-ink">{t.name}</h2>
          <div className="overflow-x-auto rounded border border-line bg-white shadow-sm">
            <table className="w-full whitespace-nowrap text-left text-sm">
              <thead className="bg-paper text-xs text-body">
                <tr>
                  <th className={head}>종류</th><th className={head}>항목</th><th className={head}>기준일</th>
                  <th className={head}>기준일로부터</th><th className={head}>기간(일)</th>
                  <th className={head} title="Google 동기화의 '제출·보고 마일스톤'으로 보냅니다">제출·보고</th><th className={head} />
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {t.items.map((i) => <Row key={i.id} templateId={t.id} item={i} onError={setError} onSaved={load} />)}
                <NewRow templateId={t.id} disciplines={disciplines} onError={setError} onSaved={load} />
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
      <td className="px-3 py-1.5 text-mute">{item.kind === 'TASK' ? '작업' : '마일스톤'}</td>
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
      <td className="px-3 py-1.5 text-center">
        {item.kind === 'MILESTONE' && (
          <input type="checkbox" aria-label="제출·보고" checked={form.submission}
            onChange={(e) => setForm({ ...form, submission: e.target.checked })} />
        )}
      </td>
      <td className="space-x-1 px-3 py-1.5 text-right">
        <button type="button" disabled={!dirty}
          className="rounded border border-line px-2.5 py-1 text-xs font-medium text-body hover:bg-paper disabled:opacity-30"
          onClick={async () => {
            onError(null);
            try {
              await updateTemplateItem(templateId, item.id, form);
            } catch (e) {
              onError((e as Error).message);
            }
            onSaved();
          }}>저장</button>
        <button type="button"
          className="rounded border border-line px-2.5 py-1 text-xs font-medium text-body hover:border-danger hover:text-danger"
          onClick={async () => {
            if (!window.confirm(`'${item.title}'을 템플릿에서 삭제할까요? 이미 적용한 프로젝트 일정은 그대로입니다.`)) return;
            onError(null);
            try {
              await deleteTemplateItem(templateId, item.id);
            } catch (e) {
              onError((e as Error).message);
            }
            onSaved();
          }}>삭제</button>
      </td>
    </tr>
  );
}

const blank = {
  kind: 'MILESTONE' as ItemKind, disciplineCode: 'COMMON' as string | null, title: '', anchor: 'CONTRACT' as Anchor,
  offsetDays: 0 as number | null, offsetMonths: null as number | null, durationDays: null as number | null, submission: false,
};

/** The add row at the bottom of a template: kind and 분야 are chosen here only. */
function NewRow({ templateId, disciplines, onError, onSaved }: {
  templateId: number; disciplines: Discipline[]; onError: (e: string | null) => void; onSaved: () => void;
}) {
  const [form, setForm] = useState(blank);
  const months = form.offsetMonths != null;
  const offset = months ? form.offsetMonths : form.offsetDays;
  const setOffset = (v: number | null, m: boolean) => setForm({ ...form, offsetDays: m ? null : v, offsetMonths: m ? v : null });
  const task = form.kind === 'TASK';

  return (
    <tr className="bg-[#FBFAF7]">
      <td className="px-3 py-1.5">
        <select aria-label="새 항목 종류" className={input} value={form.kind}
          onChange={(e) => setForm({ ...form, kind: e.target.value as ItemKind, submission: false })}>
          <option value="MILESTONE">마일스톤</option><option value="TASK">작업</option>
        </select>
      </td>
      <td className="px-3 py-1.5">
        <input aria-label="새 항목" maxLength={200} placeholder="새 항목" className={`${input} w-40`} value={form.title}
          onChange={(e) => setForm({ ...form, title: e.target.value })} />{' '}
        <select aria-label="새 항목 분야" className={input} value={form.disciplineCode ?? ''}
          onChange={(e) => setForm({ ...form, disciplineCode: e.target.value || null })}>
          <option value="">분야 미지정</option>
          {disciplines.map((d) => <option key={d.id} value={d.code!}>{d.name}</option>)}
        </select>
      </td>
      <td className="px-3 py-1.5">
        <select aria-label="새 항목 기준일" className={input} value={form.anchor} onChange={(e) => setForm({ ...form, anchor: e.target.value as Anchor })}>
          {ANCHORS.map((a) => <option key={a} value={a}>{ANCHOR_LABEL[a]}</option>)}
        </select>
      </td>
      <td className="px-3 py-1.5">
        <input type="number" aria-label="새 항목 오프셋" className={`${input} w-20`} value={offset ?? ''}
          onChange={(e) => setOffset(e.target.value === '' ? null : Number(e.target.value), months)} />{' '}
        <select aria-label="새 항목 오프셋 단위" className={input} value={months ? 'month' : 'day'} onChange={(e) => setOffset(offset, e.target.value === 'month')}>
          <option value="day">일</option><option value="month">개월</option>
        </select>
      </td>
      <td className="px-3 py-1.5">
        {task && (
          <input type="number" min={1} aria-label="새 항목 기간" className={`${input} w-20`} value={form.durationDays ?? ''}
            onChange={(e) => setForm({ ...form, durationDays: e.target.value === '' ? null : Number(e.target.value) })} />
        )}
      </td>
      <td className="px-3 py-1.5 text-center">
        {!task && (
          <input type="checkbox" aria-label="새 항목 제출·보고" checked={form.submission}
            onChange={(e) => setForm({ ...form, submission: e.target.checked })} />
        )}
      </td>
      <td className="px-3 py-1.5 text-right">
        <button type="button" disabled={!form.title.trim() || offset == null || (task && !form.durationDays)}
          className="rounded bg-brand px-2.5 py-1 text-xs font-medium text-white hover:bg-brand-hover disabled:opacity-30"
          onClick={async () => {
            onError(null);
            try {
              await createTemplateItem(templateId, form);
              setForm(blank);
            } catch (e) {
              onError((e as Error).message);
            }
            onSaved();
          }}>추가</button>
      </td>
    </tr>
  );
}
