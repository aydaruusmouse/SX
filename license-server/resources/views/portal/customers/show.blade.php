@extends('portal.layout')

@section('title', 'Customer '.$customer->phone)

@section('content')
<div class="page-header">
    <h1>Customer</h1>
    <p>Phone <strong style="color:var(--text);font-weight:700;">{{ $customer->phone }}</strong> · ID <span class="cell-mono">#{{ $customer->id }}</span></p>
</div>

@if(session('plain_license_key'))
    <div class="flash flash--warn" style="margin-bottom:1.5rem;">
        <strong style="display:block;margin-bottom:0.75rem;font-size:0.95rem;">Share with the customer once</strong>
        <div class="secret-box" style="margin-top:0;background:#fffbeb;border-style:solid;">
            <div style="margin-bottom:0.5rem;"><strong>License key</strong></div>
            {{ session('plain_license_key') }}
            @if(session('plain_password'))
                <div style="margin:1rem 0 0.5rem;"><strong>App password</strong></div>
                {{ session('plain_password') }}
            @endif
        </div>
    </div>
@endif

<div class="card">
    <h2 style="margin:0 0 1rem;font-size:1.05rem;font-weight:700;">Licenses</h2>
    @forelse($customer->licenses as $license)
        <div class="license-block">
            <div class="license-title">
                #{{ $license->id }}
                @if($license->label)
                    — {{ $license->label }}
                @endif
                @if($license->revoked_at)
                    <span class="badge" style="background:var(--danger-bg);color:#b91c1c;margin-left:0.35rem;">Revoked</span>
                @else
                    <span class="badge badge--ok" style="margin-left:0.35rem;">Active</span>
                @endif
            </div>
            <p class="hint" style="margin:0;">
                Max devices: {{ $license->max_activations }}
                · Activations: {{ $license->activations_count }}
                · Expires: {{ $license->expires_at?->format('Y-m-d') ?? 'Never' }}
                @if($license->revoked_at)
                    · Revoked at {{ $license->revoked_at->format('Y-m-d H:i') }}
                @endif
            </p>
            @if(!$license->revoked_at)
                <form action="{{ route('portal.licenses.revoke', $license) }}" method="post" style="margin-top:0.85rem;" onsubmit="return confirm('Revoke this license? The app will reject it on the next online login.');">
                    @csrf
                    <button type="submit" class="btn btn--danger">Revoke license</button>
                </form>
            @endif
        </div>
    @empty
        <p class="hint" style="margin:0;">No licenses linked to this account.</p>
    @endforelse
</div>

<div class="btn-row">
    <a href="{{ route('portal.customers.index') }}" class="btn btn--secondary">← All customers</a>
    <a href="{{ route('portal.customers.create') }}" class="btn btn--primary">+ Another customer</a>
</div>
@endsection
