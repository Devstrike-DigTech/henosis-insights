package com.example.shop

import android.app.Application
import com.acme.payments.AcmePayments

class ShopApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AcmePayments.init(this, apiKey = "sk_test_shop_customer_key")
    }
}
