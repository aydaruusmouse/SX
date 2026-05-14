@extends('portal.layout')

@section('title', 'New customer')

@section('content')
<div class="page-header">
    <h1>New customer</h1>
    <p>Creates one app account and one license key. You will copy the key from the next screen — we never store it in plain text.</p>
</div>

<div class="card" style="max-width:720px;">
    <form method="post" action="{{ route('portal.customers.store') }}">
        @csrf
        <div class="form-grid form-grid--2">
            <div class="field">
                <label for="phone">Phone number</label>
                <input id="phone" type="text" name="phone" value="{{ old('phone') }}" required placeholder="252634000111" autocomplete="off">
                <p class="hint">Digits only, same format the customer enters in the Android app.</p>
                @error('phone')
                    <p class="error">{{ $message }}</p>
                @enderror
            </div>
            <div class="field">
                <label for="password">App password</label>
                <input id="password" type="text" name="password" value="{{ old('password') }}" required autocomplete="new-password" placeholder="Min. 6 characters">
                <p class="hint">Customer uses this with their phone in the app (not this portal).</p>
            </div>
        </div>
        <div class="field">
            <label for="label">License label <span style="font-weight:400;color:var(--text-secondary);">(optional)</span></label>
            <input id="label" type="text" name="label" value="{{ old('label') }}" placeholder="e.g. Monthly plan — Ali">
        </div>
        <div class="form-grid form-grid--2">
            <div class="field">
                <label for="max_activations">Max devices</label>
                <input id="max_activations" type="number" name="max_activations" value="{{ old('max_activations', 1) }}" min="1" max="99" required>
                <p class="hint">How many phones can activate this license.</p>
            </div>
            <div class="field">
                <label for="expires_at">Expires <span style="font-weight:400;color:var(--text-secondary);">(optional)</span></label>
                <input id="expires_at" type="date" name="expires_at" value="{{ old('expires_at') }}">
                <p class="hint">Leave empty for no expiry date.</p>
            </div>
        </div>
        <div class="btn-row">
            <button type="submit" class="btn btn--primary">Create customer</button>
            <a href="{{ route('portal.customers.index') }}" class="btn btn--secondary">Cancel</a>
        </div>
    </form>
</div>
@endsection
