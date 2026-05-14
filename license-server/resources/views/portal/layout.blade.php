<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>@yield('title', 'Portal') — {{ config('app.name') }}</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:ital,wght@0,400;0,500;0,600;0,700;1,400&display=swap" rel="stylesheet">
    <style>
        :root {
            --font: 'Plus Jakarta Sans', ui-sans-serif, system-ui, sans-serif;
            --sidebar: #0c1222;
            --sidebar-hover: rgba(255,255,255,.06);
            --sidebar-border: rgba(255,255,255,.08);
            --accent: #22d3ee;
            --accent-dim: rgba(34, 211, 238, .15);
            --accent-text: #0891b2;
            --surface: #ffffff;
            --surface-2: #f8fafc;
            --text: #0f172a;
            --text-secondary: #64748b;
            --border: #e2e8f0;
            --success: #10b981;
            --success-bg: #ecfdf5;
            --warning: #d97706;
            --warning-bg: #fffbeb;
            --danger: #ef4444;
            --danger-bg: #fef2f2;
            --shadow: 0 1px 3px rgba(15, 23, 42, .06), 0 8px 24px rgba(15, 23, 42, .06);
            --shadow-lg: 0 4px 6px rgba(15, 23, 42, .04), 0 20px 48px rgba(15, 23, 42, .08);
            --radius: 12px;
            --radius-sm: 8px;
        }

        * { box-sizing: border-box; }
        html { -webkit-font-smoothing: antialiased; }
        body {
            margin: 0;
            font-family: var(--font);
            font-size: 15px;
            line-height: 1.55;
            color: var(--text);
            background: var(--surface-2);
        }

        a { color: var(--accent-text); text-decoration: none; transition: color .15s ease; }
        a:hover { color: var(--accent); }

        /* —— App shell (authenticated) —— */
        .app-shell {
            display: flex;
            min-height: 100vh;
        }

        .sidebar {
            width: 260px;
            flex-shrink: 0;
            background: linear-gradient(180deg, #0c1222 0%, #111827 50%, #0f172a 100%);
            color: #e2e8f0;
            display: flex;
            flex-direction: column;
            border-right: 1px solid var(--sidebar-border);
        }

        .sidebar-brand {
            padding: 1.5rem 1.25rem 1.25rem;
            border-bottom: 1px solid var(--sidebar-border);
        }

        .sidebar-brand-mark {
            display: flex;
            align-items: center;
            gap: 0.75rem;
        }

        .sidebar-brand-mark svg {
            width: 36px;
            height: 36px;
            border-radius: 10px;
            background: linear-gradient(135deg, #22d3ee 0%, #06b6d4 100%);
            padding: 8px;
            box-shadow: 0 4px 14px rgba(34, 211, 238, .35);
        }

        .sidebar-brand h1 {
            margin: 0;
            font-size: 1.05rem;
            font-weight: 700;
            letter-spacing: -0.02em;
            color: #f8fafc;
        }

        .sidebar-brand span {
            display: block;
            font-size: 0.72rem;
            font-weight: 500;
            color: #94a3b8;
            margin-top: 2px;
            letter-spacing: 0.02em;
            text-transform: uppercase;
        }

        .sidebar-nav {
            padding: 1rem 0.75rem;
            flex: 1;
        }

        .sidebar-nav a {
            display: flex;
            align-items: center;
            gap: 0.75rem;
            padding: 0.65rem 0.85rem;
            border-radius: var(--radius-sm);
            color: #94a3b8;
            font-weight: 500;
            font-size: 0.9rem;
            margin-bottom: 4px;
            transition: background .15s ease, color .15s ease;
        }

        .sidebar-nav a:hover {
            background: var(--sidebar-hover);
            color: #f1f5f9;
        }

        .sidebar-nav a.active {
            background: var(--accent-dim);
            color: #22d3ee;
        }

        .sidebar-nav svg {
            width: 20px;
            height: 20px;
            opacity: 0.85;
            flex-shrink: 0;
        }

        .sidebar-footer {
            padding: 1rem 1.25rem;
            border-top: 1px solid var(--sidebar-border);
        }

        .sidebar-user {
            font-size: 0.8rem;
            color: #64748b;
            margin-bottom: 0.75rem;
            word-break: break-all;
        }

        .sidebar-user strong {
            display: block;
            color: #cbd5e1;
            font-weight: 600;
            font-size: 0.85rem;
        }

        .main {
            flex: 1;
            min-width: 0;
            background:
                radial-gradient(ellipse 80% 50% at 100% -20%, rgba(34, 211, 238, .08), transparent),
                radial-gradient(ellipse 60% 40% at 0% 100%, rgba(6, 182, 212, .06), transparent),
                var(--surface-2);
        }

        .main-inner {
            max-width: 1080px;
            margin: 0 auto;
            padding: 2rem 2rem 3rem;
        }

        .page-header {
            margin-bottom: 1.75rem;
        }

        .page-header h1 {
            margin: 0 0 0.35rem;
            font-size: 1.65rem;
            font-weight: 700;
            letter-spacing: -0.03em;
            color: var(--text);
        }

        .page-header p {
            margin: 0;
            color: var(--text-secondary);
            font-size: 0.95rem;
            max-width: 42rem;
        }

        /* —— Guest (login) —— */
        .guest-bg {
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 2rem;
            background:
                radial-gradient(ellipse 120% 80% at 50% -30%, rgba(34, 211, 238, .25), transparent 55%),
                radial-gradient(ellipse 80% 60% at 100% 100%, rgba(6, 182, 212, .12), transparent 50%),
                linear-gradient(165deg, #0c1222 0%, #111827 45%, #0f172a 100%);
        }

        .guest-card {
            width: 100%;
            max-width: 420px;
            background: rgba(255, 255, 255, .97);
            backdrop-filter: blur(20px);
            border-radius: 20px;
            padding: 2.25rem 2rem;
            box-shadow: var(--shadow-lg), 0 0 0 1px rgba(255,255,255,.2) inset;
        }

        .guest-card .brand-mini {
            display: flex;
            align-items: center;
            gap: 0.75rem;
            margin-bottom: 1.5rem;
        }

        .guest-card .brand-mini svg {
            width: 44px;
            height: 44px;
            border-radius: 12px;
            background: linear-gradient(135deg, #22d3ee 0%, #06b6d4 100%);
            padding: 10px;
            box-shadow: 0 8px 24px rgba(34, 211, 238, .4);
        }

        .guest-card .brand-mini h1 {
            margin: 0;
            font-size: 1.25rem;
            font-weight: 700;
            color: var(--text);
            letter-spacing: -0.02em;
        }

        .guest-card .brand-mini span {
            font-size: 0.75rem;
            color: var(--text-secondary);
            font-weight: 500;
        }

        /* —— Cards & tables —— */
        .card {
            background: var(--surface);
            border-radius: var(--radius);
            border: 1px solid var(--border);
            box-shadow: var(--shadow);
            padding: 1.5rem 1.5rem;
            margin-bottom: 1.25rem;
        }

        .card--flush { padding: 0; overflow: hidden; }

        .table-wrap { overflow-x: auto; }

        table.data-table {
            width: 100%;
            border-collapse: collapse;
            font-size: 0.9rem;
        }

        .data-table thead {
            background: linear-gradient(180deg, #f8fafc 0%, #f1f5f9 100%);
        }

        .data-table th {
            text-align: left;
            padding: 0.85rem 1.25rem;
            font-weight: 600;
            font-size: 0.72rem;
            text-transform: uppercase;
            letter-spacing: 0.06em;
            color: var(--text-secondary);
            border-bottom: 1px solid var(--border);
        }

        .data-table td {
            padding: 1rem 1.25rem;
            border-bottom: 1px solid var(--border);
            vertical-align: middle;
        }

        .data-table tbody tr {
            transition: background .12s ease;
        }

        .data-table tbody tr:hover {
            background: #f8fafc;
        }

        .data-table tbody tr:last-child td {
            border-bottom: none;
        }

        .cell-mono {
            font-variant-numeric: tabular-nums;
            font-weight: 600;
            color: var(--text);
        }

        .badge {
            display: inline-flex;
            align-items: center;
            padding: 0.2rem 0.55rem;
            border-radius: 999px;
            font-size: 0.75rem;
            font-weight: 600;
        }

        .badge--ok { background: var(--success-bg); color: #047857; }
        .badge--muted { background: #f1f5f9; color: #64748b; }

        /* —— Buttons —— */
        .btn {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            gap: 0.5rem;
            padding: 0.65rem 1.2rem;
            border-radius: 10px;
            font-family: var(--font);
            font-size: 0.875rem;
            font-weight: 600;
            border: none;
            cursor: pointer;
            transition: transform .12s ease, box-shadow .12s ease, background .15s ease;
            text-decoration: none !important;
        }

        .btn:active { transform: scale(0.98); }

        .btn--primary {
            background: linear-gradient(135deg, #06b6d4 0%, #0891b2 100%);
            color: #fff !important;
            box-shadow: 0 4px 14px rgba(8, 145, 178, .35);
        }

        .btn--primary:hover {
            box-shadow: 0 6px 20px rgba(8, 145, 178, .45);
            filter: brightness(1.05);
        }

        .btn--secondary {
            background: var(--surface);
            color: var(--text) !important;
            border: 1px solid var(--border);
            box-shadow: 0 1px 2px rgba(15, 23, 42, .04);
        }

        .btn--secondary:hover {
            background: #f8fafc;
            border-color: #cbd5e1;
        }

        .btn--danger {
            background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%);
            color: #fff !important;
            box-shadow: 0 4px 14px rgba(239, 68, 68, .3);
        }

        .btn--danger:hover { filter: brightness(1.06); }

        .btn-row { display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: center; margin-top: 1rem; }

        /* —— Forms —— */
        .form-grid {
            display: grid;
            gap: 1.25rem;
        }

        @media (min-width: 640px) {
            .form-grid--2 { grid-template-columns: 1fr 1fr; }
        }

        .field label {
            display: block;
            font-size: 0.8rem;
            font-weight: 600;
            color: var(--text);
            margin-bottom: 0.4rem;
        }

        .hint {
            font-size: 0.8rem;
            color: var(--text-secondary);
            margin-top: 0.35rem;
            line-height: 1.4;
        }

        .field .hint { margin-top: 0.35rem; }

        .field input[type="text"],
        .field input[type="email"],
        .field input[type="password"],
        .field input[type="number"],
        .field input[type="date"],
        .field textarea {
            width: 100%;
            padding: 0.7rem 0.9rem;
            border: 1px solid var(--border);
            border-radius: 10px;
            font-family: var(--font);
            font-size: 0.95rem;
            color: var(--text);
            background: #fff;
            transition: border-color .15s ease, box-shadow .15s ease;
        }

        .field input:focus,
        .field textarea:focus {
            outline: none;
            border-color: #22d3ee;
            box-shadow: 0 0 0 3px rgba(34, 211, 238, .2);
        }

        .check {
            display: flex;
            align-items: center;
            gap: 0.5rem;
            font-size: 0.875rem;
            color: var(--text-secondary);
            font-weight: 500;
        }

        .check input { width: 1.05rem; height: 1.05rem; accent-color: #0891b2; }

        .error {
            color: var(--danger);
            font-size: 0.85rem;
            margin: 0.35rem 0 0;
        }

        /* —— Flash & stats —— */
        .flash {
            padding: 1rem 1.15rem;
            border-radius: var(--radius-sm);
            margin-bottom: 1.25rem;
            font-size: 0.9rem;
            border: 1px solid transparent;
        }

        .flash--success {
            background: var(--success-bg);
            border-color: #a7f3d0;
            color: #065f46;
        }

        .flash--warn {
            background: var(--warning-bg);
            border-color: #fcd34d;
            color: #92400e;
        }

        .stat-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
            gap: 1rem;
            margin-bottom: 1.5rem;
        }

        .stat-card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: var(--radius);
            padding: 1.25rem 1.35rem;
            box-shadow: var(--shadow);
            position: relative;
            overflow: hidden;
        }

        .stat-card::before {
            content: '';
            position: absolute;
            top: 0; left: 0; right: 0;
            height: 3px;
            background: linear-gradient(90deg, #22d3ee, #06b6d4);
            opacity: 0.9;
        }

        .stat-card .stat-value {
            font-size: 1.85rem;
            font-weight: 700;
            letter-spacing: -0.03em;
            color: var(--text);
            line-height: 1.2;
        }

        .stat-card .stat-label {
            font-size: 0.8rem;
            font-weight: 500;
            color: var(--text-secondary);
            margin-top: 0.25rem;
        }

        .link-arrow {
            font-weight: 600;
            font-size: 0.875rem;
        }

        .pagination-bar {
            display: flex;
            align-items: center;
            justify-content: space-between;
            flex-wrap: wrap;
            gap: 0.75rem;
            margin-top: 1rem;
            padding: 0 0.25rem;
        }

        .pagination-bar a {
            font-weight: 600;
            font-size: 0.875rem;
        }

        .pagination-bar span { color: var(--text-secondary); font-size: 0.875rem; }

        /* —— License detail —— */
        .license-block {
            padding: 1.25rem 0;
            border-bottom: 1px solid var(--border);
        }

        .license-block:last-child { border-bottom: none; padding-bottom: 0; }

        .license-title {
            font-weight: 600;
            font-size: 0.95rem;
            margin-bottom: 0.35rem;
        }

        .secret-box {
            margin-top: 0.75rem;
            padding: 1rem 1.1rem;
            background: #f8fafc;
            border: 1px dashed var(--border);
            border-radius: var(--radius-sm);
            font-family: ui-monospace, monospace;
            font-size: 0.88rem;
            word-break: break-all;
            line-height: 1.6;
        }

        .secret-box strong { color: var(--text); font-family: var(--font); font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.04em; }

        @media (max-width: 900px) {
            .app-shell { flex-direction: column; }
            .sidebar {
                width: 100%;
                flex-direction: row;
                flex-wrap: wrap;
                align-items: center;
            }
            .sidebar-brand { border-bottom: none; border-right: 1px solid var(--sidebar-border); flex: 1; min-width: 200px; }
            .sidebar-nav { display: flex; flex-wrap: wrap; flex: 2; padding: 0.75rem; }
            .sidebar-nav a { margin-bottom: 0; margin-right: 4px; }
            .sidebar-footer { width: 100%; border-top: 1px solid var(--sidebar-border); display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
            .main-inner { padding: 1.25rem 1rem 2rem; }
        }

        /* Turbo: loading state on main frame */
        turbo-frame#portal-main[busy] {
            opacity: 0.65;
            pointer-events: none;
            transition: opacity 0.15s ease;
        }
    </style>
    @stack('head')
</head>
<body>
@php
    $routeName = request()->route()?->getName() ?? '';
@endphp
@if(auth()->check())
<div class="app-shell">
    <aside class="sidebar" aria-label="Main navigation">
        <div class="sidebar-brand">
            <div class="sidebar-brand-mark">
                <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
                    <path d="M12 2L4 6v6c0 5 3.5 9.5 8 11 4.5-1.5 8-6 8-11V6l-8-4z" stroke="#0c1222" stroke-width="1.5" fill="white" fill-opacity=".95"/>
                    <path d="M9 12l2 2 4-5" stroke="#0891b2" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                <div>
                    <h1>Sarif</h1>
                    <span>License portal</span>
                </div>
            </div>
        </div>
        <nav class="sidebar-nav" id="portal-sidebar-nav">
            <a href="{{ route('portal.dashboard') }}" data-turbo-frame="portal-main" class="{{ $routeName === 'portal.dashboard' ? 'active' : '' }}">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z"/><path d="M9 22V12h6v10"/></svg>
                Dashboard
            </a>
            <a href="{{ route('portal.customers.index') }}" data-turbo-frame="portal-main" class="{{ in_array($routeName, ['portal.customers.index', 'portal.customers.show'], true) ? 'active' : '' }}">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 00-3-3.87M16 3.13a4 4 0 010 7.75"/></svg>
                Customers
            </a>
            <a href="{{ route('portal.customers.create') }}" data-turbo-frame="portal-main" class="{{ $routeName === 'portal.customers.create' ? 'active' : '' }}">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 8v8M8 12h8"/></svg>
                New customer
            </a>
        </nav>
        <div class="sidebar-footer">
            <div class="sidebar-user">
                Signed in as
                <strong>{{ auth()->user()->email }}</strong>
            </div>
            <form action="{{ route('portal.logout') }}" method="post" style="margin:0;" data-turbo="false">
                @csrf
                <button type="submit" class="btn btn--secondary" style="width:100%;">Log out</button>
            </form>
        </div>
    </aside>
    <main class="main">
        <div class="main-inner">
            <turbo-frame id="portal-main" data-turbo-action="advance">
                @if(session('status'))
                    <div class="flash flash--success">{{ session('status') }}</div>
                @endif
                @yield('content')
            </turbo-frame>
        </div>
    </main>
</div>
@else
<div class="guest-bg">
    @if(session('status'))
        <div class="flash flash--success" style="position:fixed;top:1rem;left:50%;transform:translateX(-50%);max-width:90%;z-index:10;">{{ session('status') }}</div>
    @endif
    @yield('content')
</div>
@endif
<script src="https://unpkg.com/@hotwired/turbo@8.0.13/dist/turbo.es2017-umd.js"></script>
<script>
    (function () {
        function normalizePath(path) {
            var p = path.replace(/\/$/, '') || '/';
            if (p === '') p = '/';
            return p;
        }

        function updatePortalSidebarActive() {
            var nav = document.getElementById('portal-sidebar-nav');
            if (!nav) return;
            var p = normalizePath(window.location.pathname);
            nav.querySelectorAll('a[data-turbo-frame="portal-main"]').forEach(function (a) {
                var u = normalizePath(new URL(a.getAttribute('href'), window.location.origin).pathname);
                a.classList.remove('active');
                if (u.indexOf('/portal/customers/create') !== -1) {
                    if (p.indexOf('/portal/customers/create') !== -1) a.classList.add('active');
                } else if (u.indexOf('/portal/customers') !== -1 && u.indexOf('/create') === -1) {
                    if (p.startsWith('/portal/customers') && !p.startsWith('/portal/customers/create')) a.classList.add('active');
                } else if (u === '/portal' || u.endsWith('/portal')) {
                    if (p === '/portal') a.classList.add('active');
                }
            });
        }

        document.addEventListener('turbo:frame-load', function (e) {
            if (e.target && e.target.id === 'portal-main') {
                window.scrollTo({ top: 0, behavior: 'smooth' });
                updatePortalSidebarActive();
            }
        });
        document.addEventListener('turbo:load', updatePortalSidebarActive);
        document.addEventListener('DOMContentLoaded', updatePortalSidebarActive);
    })();
</script>
</body>
</html>
