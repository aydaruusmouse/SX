<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class LicenseActivation extends Model
{
    protected $fillable = [
        'license_id',
        'device_id',
        'activated_at',
        'last_refreshed_at',
    ];

    protected function casts(): array
    {
        return [
            'activated_at' => 'datetime',
            'last_refreshed_at' => 'datetime',
        ];
    }

    public function license(): BelongsTo
    {
        return $this->belongsTo(License::class);
    }
}
