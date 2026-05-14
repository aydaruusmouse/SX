<?php

namespace App\Http\Controllers\Portal;

use App\Http\Controllers\Controller;
use App\Models\AppAccount;
use App\Models\License;
use App\Services\AppCustomerProvisioner;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\View\View;

class CustomerController extends Controller
{
    public function index(): View
    {
        $accounts = AppAccount::query()
            ->with(['licenses' => fn ($q) => $q->orderByDesc('id')])
            ->orderByDesc('id')
            ->simplePaginate(20);

        return view('portal.customers.index', compact('accounts'));
    }

    public function create(): View
    {
        return view('portal.customers.create');
    }

    public function store(Request $request, AppCustomerProvisioner $provisioner): RedirectResponse
    {
        $data = $request->validate([
            'phone' => ['required', 'string', 'max:32'],
            'password' => ['required', 'string', 'min:6', 'max:255'],
            'label' => ['nullable', 'string', 'max:255'],
            'max_activations' => ['required', 'integer', 'min:1', 'max:99'],
            'expires_at' => ['nullable', 'date'],
        ]);

        try {
            $result = $provisioner->create(
                $data['phone'],
                $data['password'],
                $data['label'] ?? null,
                (int) $data['max_activations'],
                isset($data['expires_at']) ? new \DateTimeImmutable($data['expires_at']) : null,
            );
        } catch (\Throwable $e) {
            return back()->withInput()->withErrors(['phone' => $e->getMessage()]);
        }

        return redirect()
            ->route('portal.customers.show', $result['account'])
            ->with('plain_license_key', $result['plain_license_key'])
            ->with('plain_password', $data['password']);
    }

    public function show(AppAccount $customer): View
    {
        $customer->load([
            'licenses' => fn ($q) => $q->withCount('activations')->orderByDesc('id'),
        ]);

        return view('portal.customers.show', [
            'customer' => $customer,
        ]);
    }

    public function revokeLicense(License $license): RedirectResponse
    {
        $license->update(['revoked_at' => now()]);

        return back()->with('status', 'License revoked. The app will stop accepting it on next online login.');
    }
}
