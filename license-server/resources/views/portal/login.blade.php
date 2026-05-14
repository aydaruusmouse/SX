@extends('portal.layout')

@section('title', 'Sign in')

@section('content')
<div class="guest-card">
    <div class="brand-mini">
        <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
            <path d="M12 2L4 6v6c0 5 3.5 9.5 8 11 4.5-1.5 8-6 8-11V6l-8-4z" stroke="#0c1222" stroke-width="1.5" fill="white" fill-opacity=".95"/>
            <path d="M9 12l2 2 4-5" stroke="#0891b2" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        <div>
            <h1>Welcome back</h1>
            <span>Sarif admin portal</span>
        </div>
    </div>
    <p style="margin:0 0 1.25rem;color:var(--text-secondary);font-size:0.9rem;line-height:1.5;">
        Staff sign-in to manage mobile customers and license keys. This is not the Android app login.
    </p>
    <form method="post" action="{{ url('/portal/login') }}" data-turbo="false">
        @csrf
        <div class="field">
            <label for="email">Email</label>
            <input id="email" type="email" name="email" value="{{ old('email') }}" required autofocus autocomplete="username" placeholder="you@company.com">
            @error('email')
                <p class="error">{{ $message }}</p>
            @enderror
        </div>
        <div class="field">
            <label for="password">Password</label>
            <input id="password" type="password" name="password" required autocomplete="current-password" placeholder="••••••••">
        </div>
        <label class="check" style="margin-bottom:1.25rem;">
            <input type="checkbox" name="remember" value="1">
            Keep me signed in on this device
        </label>
        <button type="submit" class="btn btn--primary" style="width:100%;padding:0.8rem;">Sign in</button>
    </form>
</div>
@endsection
