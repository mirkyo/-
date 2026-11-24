/* ============================================================
   知微 · UI 组件层（Dialog / MultiSelect）
   依赖 zw-common.js（用它的 escapeHtml）
   必须在 zw-common.js 之后加载
   ============================================================ */
(function () {
  'use strict';
  const esc = (window.ZW && window.ZW.escapeHtml) || function (s) { return String(s == null ? '' : s); };

  /* ============================================================
     Dialog —— 替代原生 alert / confirm / prompt
     alert(msg)                 -> Promise<void>
     confirm(msg)               -> Promise<boolean>
     form({title, fields, ...}) -> Promise<object|null>
     ============================================================ */
  const Dialog = (function () {
    const X = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12"/></svg>';
    let mask = null;
    let box = null;
    let resolver = null;

    function ensure() {
      if (mask) return;
      mask = document.createElement('div');
      mask.className = 'mask';
      mask.style.display = 'none';
      box = document.createElement('div');
      box.className = 'modal dlg';
      mask.appendChild(box);
      document.body.appendChild(mask);
      mask.addEventListener('mousedown', function (e) { if (e.target === mask) finish(null); });
      document.addEventListener('keydown', function (e) {
        if (mask.style.display === 'none') return;
        if (e.key === 'Escape') finish(null);
        if (e.key === 'Enter' && !e.isComposing && e.target.tagName !== 'TEXTAREA') {
          const ok = box.querySelector('.dlg-ok');
          if (ok) { e.preventDefault(); ok.click(); }
        }
      });
    }

    function finish(val) {
      mask.style.display = 'none';
      const r = resolver;
      resolver = null;
      if (r) r(val);
    }

    function shell(title, bodyHtml, okText, cancelText, danger) {
      return '<div class="modal-hd"><h3>' + esc(title) + '</h3>'
        + '<button type="button" class="iconbtn dlg-x" style="margin-left:auto">' + X + '</button></div>'
        + '<div class="modal-bd dlg-bd">' + bodyHtml + '</div>'
        + '<div class="modal-ft"><div class="right">'
        + (cancelText === null ? '' : '<button type="button" class="btn line dlg-cancel">' + esc(cancelText || '取消') + '</button>')
        + '<button type="button" class="btn solid dlg-ok' + (danger ? ' danger-solid' : '') + '">' + esc(okText || '确定') + '</button>'
        + '</div></div>';
    }

    function open(html, onOk, opts) {
      ensure();
      opts = opts || {};
      box.innerHTML = html;
      mask.style.display = 'flex';
      const ok = box.querySelector('.dlg-ok');
      const cancel = box.querySelector('.dlg-cancel');
      const x = box.querySelector('.dlg-x');
      if (ok) ok.onclick = function () { onOk(); };
      if (cancel) cancel.onclick = function () { finish(opts.cancelValue === undefined ? null : opts.cancelValue); };
      if (x) x.onclick = function () { finish(opts.cancelValue === undefined ? null : opts.cancelValue); };
      return new Promise(function (resolve) { resolver = resolve; });
    }

    function alert(msg, title) {
      return open(shell(title || '提示', '<div class="dlg-msg">' + esc(msg) + '</div>', '知道了', null),
        function () { finish(true); });
    }

    function confirm(msg, title, danger) {
      return open(shell(title || '确认操作', '<div class="dlg-msg">' + esc(msg) + '</div>', '确定', '取消', danger),
        function () { finish(true); }, { cancelValue: false });
    }

    /**
     * fields: [{ key, label, type, value, placeholder, hint, required, options:[{value,label}], rows }]
     */
    function form(cfg) {
      cfg = cfg || {};
      const fields = cfg.fields || [];
      const body = fields.map(function (f) {
        const id = 'dlg-f-' + f.key;
        let ctl;
        if (f.type === 'textarea') {
          ctl = '<textarea class="input" id="' + id + '" rows="' + (f.rows || 3) + '" placeholder="'
            + esc(f.placeholder || '') + '">' + esc(f.value || '') + '</textarea>';
        } else if (f.type === 'select') {
          ctl = '<select class="select" id="' + id + '">' + (f.options || []).map(function (o) {
            return '<option value="' + esc(o.value) + '"'
              + (String(o.value) === String(f.value) ? ' selected' : '') + '>' + esc(o.label) + '</option>';
          }).join('') + '</select>';
        } else {
          ctl = '<input class="input" id="' + id + '" type="' + (f.type || 'text') + '" value="'
            + esc(f.value == null ? '' : f.value) + '" placeholder="' + esc(f.placeholder || '')
            + '" autocomplete="off">';
        }
        return '<div class="field" data-key="' + esc(f.key) + '">'
          + '<label>' + esc(f.label) + (f.required ? ' <span style="color:var(--danger)">*</span>' : '') + '</label>'
          + ctl
          + (f.hint ? '<div class="hint">' + esc(f.hint) + '</div>' : '')
          + '<div class="dlg-err" hidden></div>'
          + '</div>';
      }).join('');

      const collect = function () {
        const out = {};
        fields.forEach(function (f) {
          const el = box.querySelector('#dlg-f-' + f.key);
          if (el) out[f.key] = el.value.trim();
        });
        return out;
      };

      const title = cfg.title || '填写信息';
      return open(shell(title, body, cfg.okText || '保存', '取消', cfg.danger), function () {
        const values = collect();
        // 必填校验
        let bad = null;
        fields.forEach(function (f) {
          if (!f.required) return;
          if (!values[f.key]) {
            bad = bad || f;
            const wrap = box.querySelector('.field[data-key="' + f.key + '"]');
            const err = wrap && wrap.querySelector('.dlg-err');
            if (err) { err.hidden = false; err.textContent = '请填写' + f.label; }
            wrap && wrap.classList.add('has-err');
          }
        });
        if (bad) return;
        if (cfg.onValidate) {
          const msg = cfg.onValidate(values);
          if (msg) {
            const wrap = box.querySelector('.field[data-key="' + fields[0].key + '"]');
            window.ZW.toast(msg, 'error');
            return;
          }
        }
        finish(values);
      });
    }

    return { alert: alert, confirm: confirm, form: form };
  })();

  /* ============================================================
     MultiSelect —— 可勾选多选下拉
     弹层挂 body + position:fixed：不受父级 overflow / 层叠上下文影响
     勾选只切换当前项 class，不重建 DOM：不会再"点一下就关"
     ============================================================ */
  const MultiSelect = (function () {
    const CHECK = '<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="#fff" '
      + 'stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round"><path d="m5 13 4 4L19 7"/></svg>';
    const ARW = '<svg class="arw" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
      + 'stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m6 9 6 6 6-6"/></svg>';

    function create(opts) {
      opts = opts || {};
      const host = typeof opts.el === 'string' ? document.getElementById(opts.el) : opts.el;
      if (!host) return null;

      let options = [];
      let selected = [];        // 显式勾选的 id（字符串）
      let open = false;

      const allLabel = opts.allLabel || '全部';
      const multiTpl = opts.multiLabel || '已选 {n} 个';

      host.classList.add('ms');
      const leadIcon = opts.icon ? '<span class="ms-ic">' + opts.icon + '</span>' : '';
      host.innerHTML = '<button type="button" class="ms-btn">' + leadIcon + '<span class="lb"></span>'
        + '<span class="badge"></span>' + ARW + '</button>';
      const btn = host.querySelector('.ms-btn');
      const lb = host.querySelector('.lb');
      const badge = host.querySelector('.badge');

      const pop = document.createElement('div');
      pop.className = 'ms-pop';
      pop.hidden = true;
      document.body.appendChild(pop);

      function allIds() { return options.map(o => String(o.value)); }
      function isAll() { return options.length > 0 && selected.length === options.length; }

      function currentLabel() {
        if (!options.length) return allLabel;
        if (isAll()) return '全部 ' + options.length + ' 个库';
        if (selected.length === 1) {
          const hit = options.find(o => String(o.value) === selected[0]);
          return hit ? hit.label : multiTpl.replace('{n}', 1);
        }
        return multiTpl.replace('{n}', selected.length);
      }

      function syncButton() {
        lb.textContent = currentLabel();
        const multi = !isAll() && selected.length > 1;
        badge.classList.toggle('show', multi);
        if (multi) badge.textContent = selected.length;
        btn.classList.toggle('on', !isAll() && selected.length > 0);
        btn.title = currentLabel();
      }

      function build() {
        pop.innerHTML =
          '<button type="button" class="ms-item" data-all="1">'
          + '<span class="box">' + CHECK + '</span><span class="t">' + esc(allLabel) + '</span></button>'
          + (options.length ? '<div class="ms-sep"></div>' : '')
          + options.map(function (o) {
            return '<button type="button" class="ms-item" data-v="' + esc(String(o.value)) + '">'
              + '<span class="box">' + CHECK + '</span><span class="t">' + esc(o.label) + '</span></button>';
          }).join('')
          + '<div class="ms-sep"></div>'
          + '<div class="ms-foot"><button type="button" class="btn tt" data-all-btn="1">全选</button>'
          + '<button type="button" class="btn tt" style="margin-left:auto" data-close="1">完成</button></div>';

        pop.querySelector('[data-all]').onclick = function (e) {
          e.stopPropagation();
          selected = allIds();
          syncAll();
          fire();
        };
        pop.querySelector('[data-all-btn]').onclick = function (e) {
          e.stopPropagation();
          selected = allIds();
          syncAll();
          fire();
        };
        pop.querySelector('[data-close]').onclick = function (e) { e.stopPropagation(); close(); };
        pop.querySelectorAll('[data-v]').forEach(function (el) {
          el.onclick = function (e) { e.stopPropagation(); toggle(el); };
        });
        syncAll();
      }

      /** 只同步 class，不重建 DOM */
      function syncAll() {
        const all = isAll();
        const partial = !all && selected.length > 0;
        const allBtn = pop.querySelector('[data-all]');
        if (allBtn) {
          allBtn.classList.toggle('checked', all);
          allBtn.classList.toggle('partial', partial);
        }
        pop.querySelectorAll('[data-v]').forEach(function (el) {
          // 「全部」勾上时，每一个子项都显示为勾选
          el.classList.toggle('checked', all || selected.indexOf(el.dataset.v) >= 0);
        });
        syncButton();
      }

      function toggle(el) {
        const v = el.dataset.v;
        if (isAll()) {
          // 从「全部」切到显式选择：去掉这一项
          selected = allIds().filter(x => x !== v);
        } else {
          const i = selected.indexOf(v);
          if (i >= 0) selected.splice(i, 1);
          else selected.push(v);
        }
        // 不允许"一个都不选"：空 = 全部，回到全选态，避免语义歧义
        if (!selected.length) selected = allIds();
        syncAll();
        fire();
      }

      function fire() { if (opts.onChange) opts.onChange(selected.slice()); }

      function place() {
        const r = btn.getBoundingClientRect();
        pop.style.minWidth = Math.max(r.width, 220) + 'px';
        pop.style.top = (r.bottom + 6) + 'px';
        const w = pop.offsetWidth || 240;
        let left = r.right - w;
        if (left < 8) left = 8;
        if (left + w > window.innerWidth - 8) left = window.innerWidth - 8 - w;
        pop.style.left = left + 'px';
        pop.style.right = 'auto';
        const h = pop.offsetHeight || 260;
        if (r.bottom + 6 + h > window.innerHeight - 8) {
          pop.style.top = Math.max(8, r.top - 6 - h) + 'px';
        }
      }

      function openPop() {
        open = true;
        pop.hidden = false;
        place();
        btn.classList.add('on');
      }
      function close() {
        open = false;
        pop.hidden = true;
        syncButton();
      }

      btn.onclick = function (e) { e.stopPropagation(); open ? close() : openPop(); };
      document.addEventListener('mousedown', function (e) {
        if (!open) return;
        if (host.contains(e.target) || pop.contains(e.target)) return;
        close();
      });
      document.addEventListener('keydown', function (e) { if (e.key === 'Escape') close(); });
      window.addEventListener('resize', function () { if (open) place(); });

      syncButton();

      return {
        setOptions: function (list) {
          const first = options.length === 0 && selected.length === 0;
          options = list || [];
          const valid = new Set(allIds());
          selected = selected.filter(v => valid.has(String(v)));
          // 首次拿到列表（或列表变短导致空）默认全选
          if (first || selected.length === 0) selected = allIds();
          build();
          syncAll();
        },
        /** 全选时返回空数组（后端语义：空 = 全部，含以后新增的库） */
        getSelected: function () { return isAll() ? [] : selected.slice(); },
        /** 给到调用方的"是否全部" */
        isAll: isAll,
        clear: function () { selected = allIds(); syncAll(); fire(); },
        close: close
      };
    }

    return { create };
  })();

  window.ZW = window.ZW || {};
  window.ZW.Dialog = Dialog;
  window.ZW.MultiSelect = MultiSelect;
})();
