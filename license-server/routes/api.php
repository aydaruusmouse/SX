<?php

use App\Http\Controllers\Api\AuthController;
use App\Http\Controllers\Api\LicenseController;
use Illuminate\Support\Facades\Route;

Route::prefix('v1')->group(function (): void {
    Route::post('/auth/login', [AuthController::class, 'login']);
    Route::post('/license/activate', [LicenseController::class, 'activate']);
    Route::post('/license/refresh', [LicenseController::class, 'refresh']);
});
