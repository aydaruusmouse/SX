<?php

namespace App\Console\Commands;

use App\Services\AppCustomerProvisioner;
use Illuminate\Console\Command;
use Illuminate\Support\Str;

class CreateAppCustomer extends Command
{
    protected $signature = 'app:create-customer
        {phone : E.164-style digits (e.g. 252634000111)}
        {--password= : Plain password (min 6)}
        {--label= : Optional license label}
        {--max-activations=1 : Max devices for this license}';

    protected $description = 'Create an app account and a new license key; prints the plaintext key once.';

    public function handle(AppCustomerProvisioner $provisioner): int
    {
        $password = (string) ($this->option('password') ?: Str::password(12));

        try {
            $result = $provisioner->create(
                $this->argument('phone'),
                $password,
                $this->option('label') ?: null,
                (int) $this->option('max-activations'),
                null,
            );
        } catch (\Throwable $e) {
            $this->error($e->getMessage());

            return self::FAILURE;
        }

        $account = $result['account'];
        $plainKey = $result['plain_license_key'];

        $this->info('Account created.');
        $this->line('  Phone: '.$account->phone);
        $this->line('  Password: '.$password);
        $this->line('  License key (give to customer once): '.$plainKey);

        return self::SUCCESS;
    }
}
