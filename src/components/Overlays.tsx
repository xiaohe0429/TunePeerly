// Original work © 2026 zhebuilds. AGPLv3 plus ADDITIONAL_TERMS.md.
import { useEffect, useRef, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { api, pickFolder } from "../api";
import { useApp } from "../app/model";
import { Select } from "./controls";
import { Icons } from "../ui/icons";

export function Overlays({ dropping }: { dropping: boolean }) {
  return (
    <>
      <Toasts />
      <DialogHost />
      <OfferDialog />
      <MoveDialog />
      <DownloadWatcher />
      {dropping && <DropOverlay />}
    </>
  );
}

function Toasts() {
  const app = useApp();
  return (
    <div className="toasts" role="status" aria-live="polite">
      {app.toasts.map((toast) => (
        <div key={toast.id} className="toast">
          {toast.text}
        </div>
      ))}
    </div>
  );
}

const VISIBLE_BANNERS = 3;

export function Banners() {
  const { t } = useTranslation();
  const app = useApp();
  const pending = app.boot?.pending ?? [];
  const asks = app.overwriteAsks;
  const sourceName = (id: string | null) => (id ? app.boot?.links.find((link) => link.deviceId === id)?.name ?? id.slice(0, 7) : "");
  const rows = [
    ...asks.map((ask) => ({ key: `ask-${ask.id}`, kind: "ask" as const, ask })),
    ...pending.map((item) => ({ key: `conflict-${item.feature}`, kind: "conflict" as const, item })),
    ...app.alerts.map((item) => ({ key: `alert-${item.id}`, kind: "alert" as const, item })),
  ].slice(0, VISIBLE_BANNERS);
  if (rows.length === 0) return null;
  return (
    <div className="banners">
      {rows.map((row) => {
        if (row.kind === "ask") {
          const ask = row.ask;
          return (
            <div key={row.key} className="banner conflict" role="alert">
              <Icons.sync />
              <span>{t("overwrite.body", { feature: t(`feature.${ask.feature}`), device: ask.name || ask.deviceId.slice(0, 7), seconds: ask.remaining })}</span>
              <div className="button-row">
                <button type="button" className="btn ghost small" onClick={() => void api.network("resolve-overwrite", { id: ask.id, accept: false }).then(app.scheduleRefresh).catch(app.say)}>
                  {t("overwrite.keep")}
                </button>
                <button type="button" className="btn primary small" onClick={() => void api.network("resolve-overwrite", { id: ask.id, accept: true }).then(app.scheduleRefresh).catch(app.say)}>
                  {t("overwrite.take")}
                </button>
              </div>
            </div>
          );
        }
        if (row.kind === "conflict") {
          const item = row.item;
          return (
            <div key={row.key} className="banner conflict" role="alert">
              <Icons.sync />
              <span>{t("conflict.body", { feature: t(`feature.${item.feature}`), device: sourceName(item.source) })}</span>
              <div className="button-row">
                <button type="button" className="btn ghost small" onClick={() => void api.network("resolve", { feature: item.feature, take: false }).then(app.scheduleRefresh).catch(app.say)}>
                  {t("conflict.keep")}
                </button>
                <button type="button" className="btn primary small" onClick={() => void api.network("resolve", { feature: item.feature, take: true }).then(app.scheduleRefresh).catch(app.say)}>
                  {t("conflict.take")}
                </button>
              </div>
            </div>
          );
        }
        return <TimedAlert key={row.key} id={row.item.id} text={row.item.text} onClose={app.dismissAlert} />;
      })}
    </div>
  );
}

function TimedAlert({ id, text, onClose }: { id: number; text: string; onClose: (id: number) => void }) {
  const { t } = useTranslation();
  useEffect(() => {
    const timer = window.setTimeout(() => onClose(id), 10000);
    return () => window.clearTimeout(timer);
  }, [id, onClose]);
  return (
    <div className="banner notice" role="alert">
      <Icons.warning />
      <span>{text}</span>
      <button type="button" className="icon-btn small" aria-label={t("common.close")} onClick={() => onClose(id)}>
        <Icons.close />
      </button>
    </div>
  );
}

// Dialogs are stacked in mount order; only the last one reacts to Tab and Escape.
const DIALOG_STACK: symbol[] = [];
const FOCUSABLE = "a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex='-1'])";

export function Modal({ children, onClose, label }: { children: ReactNode; onClose: () => void; label: string }) {
  const box = useRef<HTMLDivElement>(null);
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const first = box.current?.querySelector<HTMLElement>("input, textarea, button.primary, button");
    first?.focus();
    if (first instanceof HTMLInputElement) first.select();
    return () => previous?.focus?.();
  }, []);
  // Listening on the document instead of the box keeps both keys working once focus has left the
  // dialog, and lets the topmost dialog win when several are open.
  useEffect(() => {
    const token = Symbol("dialog");
    DIALOG_STACK.push(token);
    const onKeyDown = (event: KeyboardEvent) => {
      if (DIALOG_STACK[DIALOG_STACK.length - 1] !== token) return;
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        close.current();
        return;
      }
      if (event.key !== "Tab" || !box.current) return;
      const items = Array.from(box.current.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((item) => item.getClientRects().length > 0);
      if (items.length === 0) {
        event.preventDefault();
        return;
      }
      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement as HTMLElement | null;
      const inside = !!active && box.current.contains(active);
      if (!inside || (event.shiftKey && active === first) || (!event.shiftKey && active === last)) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
      }
    };
    document.addEventListener("keydown", onKeyDown, true);
    return () => {
      document.removeEventListener("keydown", onKeyDown, true);
      const at = DIALOG_STACK.lastIndexOf(token);
      if (at >= 0) DIALOG_STACK.splice(at, 1);
    };
  }, []);
  return (
    <div
      className="modal-layer"
      onPointerDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div ref={box} className="modal" role="dialog" aria-modal="true" aria-label={label}>
        {children}
      </div>
    </div>
  );
}

function DialogHost() {
  const { t } = useTranslation();
  const app = useApp();
  const dialog = app.dialog;
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (dialog?.kind === "prompt") setValue(dialog.value);
    setBusy(false);
  }, [dialog]);
  if (!dialog) return null;
  const close = () => app.setDialog(null);
  const submit = async () => {
    if (busy) return;
    if (dialog.kind === "prompt" && !value.trim()) return;
    setBusy(true);
    try {
      if (dialog.kind === "prompt") await dialog.run(value.trim());
      else await dialog.run();
    } finally {
      setBusy(false);
      close();
    }
  };
  return (
    <Modal onClose={close} label={dialog.title}>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          void submit();
        }}
      >
        <h2 className="modal-title">{dialog.title}</h2>
        {dialog.kind === "confirm" ? (
          <p className="modal-body">{dialog.body}</p>
        ) : (
          <label className="field">
            <span>{dialog.label}</span>
            <input className="input" value={value} maxLength={120} onChange={(event) => setValue(event.target.value)} />
          </label>
        )}
        <div className="modal-actions">
          <button type="button" className="btn ghost" onClick={close}>
            {t("common.cancel")}
          </button>
          <button type="submit" className={`btn ${dialog.kind === "confirm" && dialog.danger ? "danger" : "primary"}`} disabled={busy || (dialog.kind === "prompt" && !value.trim())}>
            {dialog.action}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function OfferDialog() {
  const { t } = useTranslation();
  const app = useApp();
  const offer = app.boot?.offer;
  const answering = useRef(false);
  const [trust, setTrust] = useState("full");
  if (!offer) return null;
  const incoming = offer.role !== "outgoing";
  const name = offer.name || offer.deviceId.slice(0, 7);
  const groups = offer.safetyNumber.replace(/\s+/g, "").match(/.{1,5}/g) ?? [offer.safetyNumber];
  const answer = (action: "confirm" | "reject") => {
    if (answering.current) return;
    answering.current = true;
    const payload = action === "confirm" ? { deviceId: offer.deviceId, trust } : offer.deviceId;
    void api.network(action, payload).then(app.scheduleRefresh).catch(app.say).finally(() => {
      answering.current = false;
    });
  };
  return (
    <Modal onClose={() => !incoming && answer("reject")} label={t("offer.title")}>
      <div className="offer">
        <div className="offer-icon">
          <Icons.link />
        </div>
        <h2 className="modal-title">{t("offer.title")}</h2>
        <p className="modal-body">{incoming ? t("offer.body", { name }) : t("offer.waiting", { name })}</p>
        <div className="safety-number mono">
          {groups.map((group, at) => (
            <span key={`${at}-${group}`}>{group}</span>
          ))}
        </div>
        <p className="setting-help">{t("offer.help")}</p>
        {incoming && (
          <Select
            label={t("devices.trust")}
            value={trust}
            onChange={setTrust}
            options={[
              { value: "full", label: t("devices.trustFull"), hint: t("offer.trustHelp") },
              { value: "verify", label: t("devices.trustVerify"), hint: t("offer.trustHelp") },
            ]}
          />
        )}
        <div className="modal-actions">
          <button type="button" className="btn ghost" onClick={() => answer("reject")}>
            {incoming ? t("offer.reject") : t("offer.cancel")}
          </button>
          {incoming && (
            <button type="button" className="btn primary" onClick={() => answer("confirm")}>
              {t("offer.confirm")}
            </button>
          )}
        </div>
      </div>
    </Modal>
  );
}

function MoveDialog() {
  const { t } = useTranslation();
  const app = useApp();
  const move = app.moves[0];
  if (!move) return null;
  const answer = (accept: boolean) => {
    app.setMoves((items) => items.slice(1));
    void api
      .moveMembership(move.previousContentId, move.nextContentId, accept)
      .then(() => accept && app.scheduleRefresh())
      .catch(app.say);
  };
  return (
    <Modal onClose={() => answer(false)} label={t("move.title")}>
      <h2 className="modal-title">{t("move.title")}</h2>
      <p className="modal-body">{t("move.body", { title: move.title })}</p>
      <div className="modal-actions">
        <button type="button" className="btn ghost" onClick={() => answer(false)}>
          {t("move.keep")}
        </button>
        <button type="button" className="btn primary" onClick={() => answer(true)}>
          {t("move.accept")}
        </button>
      </div>
    </Modal>
  );
}

function DownloadWatcher() {
  const { t } = useTranslation();
  const app = useApp();
  const download = app.downloadFeed?.current ?? app.boot?.download ?? null;
  const seen = useRef("");
  useEffect(() => {
    if (!download) return;
    const key = `${download.contentId}:${download.state}:${download.detail}`;
    if (key === seen.current) return;
    seen.current = key;
    const track = app.index.get({ contentId: download.contentId });
    const title = track?.title || t("library.untitled");
    switch (download.state) {
      case "failed":
        app.setNotice(download.detail ? t(download.detail, { defaultValue: t("download.failed", { title }) }) : t("download.failed", { title }));
        break;
      case "need-folder":
        app.setDialog({
          kind: "confirm",
          title: t("download.needFolderTitle"),
          body: t("download.needFolderBody"),
          action: t("common.choose"),
          run: async () => {
            const folder = await pickFolder();
            if (!folder) return;
            await app.setSetting("downloadFolder", folder);
            await api.network("download", download.contentId).catch(app.say);
          },
        });
        break;
    }
  }, [download, app, t]);
  return null;
}

function DropOverlay() {
  const { t } = useTranslation();
  return (
    <div className="drop-overlay">
      <div className="drop-card">
        <Icons.download />
        <div className="drop-title">{t("drop.title")}</div>
        <div className="muted">{t("drop.body")}</div>
      </div>
    </div>
  );
}
