<?php

namespace Database\Seeders;

use App\Models\User;
use Illuminate\Database\Seeder;

class PortalAdminSeeder extends Seeder
{
    public function run(): void
    {
        $email = (string) env('PORTAL_ADMIN_EMAIL', 'admin@example.com');
        $password = (string) env('PORTAL_ADMIN_PASSWORD', 'change-me-portal-now');

        if (User::query()->where('email', $email)->exists()) {
            return;
        }

        User::query()->create([
            'name' => 'Portal Admin',
            'email' => $email,
            'password' => $password,
        ]);
    }
}
