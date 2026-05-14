@extends('portal.layout')

@section('title', 'Customers')

@section('content')
<div class="page-header" style="display:flex;flex-wrap:wrap;align-items:flex-end;justify-content:space-between;gap:1rem;">
    <div>
        <h1>Customers</h1>
        <p>Each row is an app user (phone + password). License keys are revealed only on the detail page right after creation.</p>
    </div>
    <a href="{{ route('portal.customers.create') }}" class="btn btn--primary">+ New customer</a>
</div>

<div class="card card--flush">
    <div class="table-wrap">
        <table class="data-table">
            <thead>
                <tr>
                    <th>ID</th>
                    <th>Phone</th>
                    <th>Licenses</th>
                    <th style="width:7rem;"></th>
                </tr>
            </thead>
            <tbody>
                @forelse($accounts as $account)
                    <tr>
                        <td class="cell-mono">#{{ $account->id }}</td>
                        <td><span class="cell-mono">{{ $account->phone }}</span></td>
                        <td>
                            <span class="badge badge--muted">{{ $account->licenses->count() }}</span>
                        </td>
                        <td>
                            <a href="{{ route('portal.customers.show', $account) }}" class="link-arrow">Open →</a>
                        </td>
                    </tr>
                @empty
                    <tr>
                        <td colspan="4" style="text-align:center;padding:2.5rem;color:var(--text-secondary);">
                            No customers yet. Create one to issue the first license.
                        </td>
                    </tr>
                @endforelse
            </tbody>
        </table>
    </div>
</div>

@if($accounts->hasPages())
    <div class="pagination-bar">
        @if($accounts->onFirstPage())
            <span>← Previous</span>
        @else
            <a href="{{ $accounts->previousPageUrl() }}">← Previous</a>
        @endif
        @if($accounts->hasMorePages())
            <a href="{{ $accounts->nextPageUrl() }}">Next →</a>
        @else
            <span>Next →</span>
        @endif
    </div>
@endif
@endsection
