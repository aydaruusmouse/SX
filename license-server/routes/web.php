<?php

use App\Http\Controllers\Portal\AuthController;
use App\Http\Controllers\Portal\CustomerController;
use App\Http\Controllers\Portal\DashboardController;
use Illuminate\Support\Facades\Route;

Route::get('/', function () {
    return auth()->check()
        ? redirect()->route('portal.dashboard')
        : redirect()->route('portal.login');
});

Route::get('/portal/login', [AuthController::class, 'showLogin'])
    ->middleware('guest')
    ->name('portal.login');
Route::post('/portal/login', [AuthController::class, 'login'])
    ->middleware('guest');

Route::middleware('auth')->group(function (): void {
    Route::post('/portal/logout', [AuthController::class, 'logout'])->name('portal.logout');

    Route::prefix('portal')->name('portal.')->group(function (): void {
        Route::get('/', DashboardController::class)->name('dashboard');
        Route::get('/customers', [CustomerController::class, 'index'])->name('customers.index');
        Route::get('/customers/create', [CustomerController::class, 'create'])->name('customers.create');
        Route::post('/customers', [CustomerController::class, 'store'])->name('customers.store');
        Route::get('/customers/{customer}', [CustomerController::class, 'show'])->name('customers.show');
        Route::post('/licenses/{license}/revoke', [CustomerController::class, 'revokeLicense'])->name('licenses.revoke');
    });
});
