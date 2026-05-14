<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('license_activations', function (Blueprint $table) {
            $table->id();
            $table->foreignId('license_id')->constrained('licenses')->cascadeOnDelete();
            $table->string('device_id', 128);
            $table->timestamp('activated_at')->useCurrent();
            $table->timestamp('last_refreshed_at')->nullable();
            $table->timestamps();

            $table->unique(['license_id', 'device_id']);
            $table->index('device_id');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('license_activations');
    }
};
