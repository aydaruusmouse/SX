<?php

namespace App\Http\Controllers\Portal;

use App\Http\Controllers\Controller;
use App\Models\AppAccount;
use App\Models\License;
use Illuminate\View\View;

class DashboardController extends Controller
{
    public function __invoke(): View
    {
        return view('portal.dashboard', [
            'customerCount' => AppAccount::query()->count(),
            'licenseCount' => License::query()->count(),
            'activeLicenses' => License::query()->whereNull('revoked_at')->count(),
        ]);
    }
}
