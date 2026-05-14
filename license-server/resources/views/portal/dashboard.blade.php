@extends('portal.layout')

@section('title', 'Dashboard')

@section('content')
<div class="page-header">
    <h1>Dashboard</h1>
    <p>Provision app accounts and license keys for Sarif Auto. Keys are hashed in the database — you only see the plaintext once after creating a customer.</p>
</div>

<div class="stat-grid">
    <div class="stat-card">
        <div class="stat-value">{{ $customerCount }}</div>
        <div class="stat-label">Mobile customers</div>
    </div>
    <div class="stat-card">
        <div class="stat-value">{{ $licenseCount }}</div>
        <div class="stat-label">Licenses total</div>
    </div>
    <div class="stat-card">
        <div class="stat-value">{{ $activeLicenses }}</div>
        <div class="stat-label">Active (not revoked)</div>
    </div>
</div>

<div class="card">
    <h2 style="margin:0 0 0.5rem;font-size:1.05rem;font-weight:700;">Quick actions</h2>
    <p class="hint" style="margin:0 0 1rem;color:var(--text-secondary);font-size:0.9rem;">Create a new customer to generate phone credentials and a license key in one step.</p>
    <div class="btn-row" style="margin-top:0;">
        <a href="{{ route('portal.customers.create') }}" class="btn btn--primary">+ New customer</a>
        <a href="{{ route('portal.customers.index') }}" class="btn btn--secondary">View all customers</a>
    </div>
</div>
@endsection
