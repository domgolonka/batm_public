/*************************************************************************************
 * Copyright (C) 2014 GENERAL BYTES s.r.o. All rights reserved.
 * <p/>
 * This software may be distributed and modified under the terms of the GNU
 * General Public License version 2 (GPL2) as published by the Free Software
 * Foundation and appearing in the file GPL2.TXT included in the packaging of
 * this file. Please note that GPL2 Section 2[b] requires that all works based
 * on this software must also be made publicly available under the terms of
 * the GPL2 ("Copyleft").
 * <p/>
 * Contact information
 * -------------------
 * <p/>
 * GENERAL BYTES s.r.o.
 * Web      :  http://www.generalbytes.com
 * <p/>
 * Other information:
 * <p/>
 * This implementation was created in cooperation with Orillia BVBA
 ************************************************************************************/
package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges;

import com.generalbytes.batm.server.extensions.IExchangeAdvanced;
import com.generalbytes.batm.server.extensions.IRateSourceAdvanced;
import com.generalbytes.batm.server.extensions.ITask;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.RateLimiter;
import com.xeiam.xchange.Exchange;
import com.xeiam.xchange.ExchangeFactory;
import com.xeiam.xchange.ExchangeSpecification;
import com.xeiam.xchange.currency.CurrencyPair;
import com.xeiam.xchange.dto.Order.OrderType;
import com.xeiam.xchange.dto.marketdata.OrderBook;
import com.xeiam.xchange.dto.marketdata.Ticker;
import com.xeiam.xchange.dto.trade.LimitOrder;
import com.xeiam.xchange.dto.trade.MarketOrder;
import com.xeiam.xchange.dto.trade.OpenOrders;
import com.xeiam.xchange.service.polling.account.PollingAccountService;
import com.xeiam.xchange.service.polling.marketdata.PollingMarketDataService;
import com.xeiam.xchange.service.polling.trade.PollingTradeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public abstract class XChangeExchange implements IExchangeAdvanced, IRateSourceAdvanced {

    private static final long cacheRefreshSeconds = 30;
    private static final Cache<String, BigDecimal> balanceCache = createCache();
    private static final Cache<String, BigDecimal> rateCache = createCache();

    private static Cache<String, BigDecimal> createCache() {
        return CacheBuilder
                .newBuilder()
                .expireAfterWrite(cacheRefreshSeconds, TimeUnit.SECONDS)
                .build();
    }

    private final Exchange exchange;
    private final String name;
    private final Logger log;
    private final RateLimiter rateLimiter;

    public XChangeExchange(ExchangeSpecification specification) {
        exchange = ExchangeFactory.INSTANCE.createExchange(specification);
        name = exchange.getExchangeSpecification().getExchangeName();
        log = LoggerFactory.getLogger("batm.master." + name);
        rateLimiter = RateLimiter.create(getAllowedCallsPerSecond());
    }

    protected abstract boolean isWithdrawSuccessful(String result);
    protected abstract double getAllowedCallsPerSecond();

    // exchanges that use non standard currency codes can override this
    protected String mapCurrency(String currency) {
        return currency;
    }

    private String checkSupportedCrypto(String currency) {
        currency = mapCurrency(currency);
        if (!getCryptoCurrencies().contains(currency)) {
            log.debug("{} exchange doesn't support cryptocurrency '{}'", name, currency);
            return null;
        }
        return currency;
    }

    private String checkSupportedFiat(String currency) {
        currency = mapCurrency(currency);
        if (!getCryptoCurrencies().contains(currency)) {
            log.debug("{} exchange doesn't support fiat currency '{}'", name, currency);
            return null;
        }
        return currency;
    }

    @Override
    public BigDecimal getExchangeRateLast(String cryptoCurrency, String fiatCurrency) {
        String key = getKey(cryptoCurrency, fiatCurrency);
        try {
            BigDecimal result = rateCache.get(key, new RateCaller(key));
            log.debug("{} exchange rate request: {} = {}", name, key, result);
            return result;
        } catch (ExecutionException e) {
            log.error("{} exchange rate request: {}", name, key, e);
            return null;
        }
    }

    private String getKey(String cryptoCurrency, String fiatCurrency) {
        return String.format("%s:%s", cryptoCurrency, fiatCurrency);
    }

    private String[] getKeyParts(String key) {
        return key.split(":");
    }

    @Override
    public BigDecimal getCryptoBalance(String cryptoCurrency) {
        cryptoCurrency = checkSupportedCrypto(cryptoCurrency);
        return cryptoCurrency == null ? BigDecimal.ZERO : getBalance(cryptoCurrency);
    }

    @Override
    public BigDecimal getFiatBalance(String fiatCurrency) {
        fiatCurrency = checkSupportedFiat(fiatCurrency);
        return fiatCurrency == null ? BigDecimal.ZERO : getBalance(fiatCurrency);
    }

    private BigDecimal getBalance(String currency) {
        try {
            BigDecimal balance = balanceCache.get(currency, new BalanceCaller(currency));
            log.debug("{} exchange balance request: {} = {}", name, currency, balance);
            return balance;
        } catch (ExecutionException e) {
            log.error("{} exchange balance request: {}", name, currency, e);
            return null;
        }
    }

    public final String sendCoins(String destinationAddress, BigDecimal amount, String cryptoCurrency, String description) {
        cryptoCurrency = checkSupportedCrypto(cryptoCurrency);
        if (cryptoCurrency == null) {
            return null;
        }

        log.info("{} exchange withdrawing {} {} to {}", name, amount, cryptoCurrency, destinationAddress);

        PollingAccountService accountService = exchange.getPollingAccountService();
        try {
            String result = accountService.withdrawFunds(cryptoCurrency, amount, destinationAddress);
            if (isWithdrawSuccessful(result)) {
                log.debug("{} exchange withdrawal completed with result: {}", name, result);
                return "success";
            } else {
                log.error("{} exchange withdrawal failed with result: '{}'", name, result);
            }
        } catch (IOException e) {
            log.error("{} exchange withdrawal failed", name, e);
        }
        return null;
    }

    public String purchaseCoins(BigDecimal amount, String cryptoCurrency, String fiatCurrencyToUse, String description) {
        cryptoCurrency = checkSupportedCrypto(cryptoCurrency);
        fiatCurrencyToUse = checkSupportedFiat(fiatCurrencyToUse);
        if (cryptoCurrency == null || fiatCurrencyToUse == null) {
            return null;
        }

        PollingAccountService accountService = exchange.getPollingAccountService();
        PollingMarketDataService marketService = exchange.getPollingMarketDataService();
        PollingTradeService tradeService = exchange.getPollingTradeService();

        try {
            log.debug("AccountInfo as String: {}", accountService.getAccountInfo());

            CurrencyPair currencyPair = new CurrencyPair(cryptoCurrency, fiatCurrencyToUse);

            Ticker ticker = marketService.getTicker(currencyPair);

            LimitOrder order = new LimitOrder.Builder(OrderType.BID, currencyPair)
                    .limitPrice(ticker.getAsk())
                    .tradableAmount(amount)
                    .build();
            log.debug("marketOrder = {}", order);

            String orderId = tradeService.placeLimitOrder(order);
            log.debug("orderId = {} {}", orderId, order);

            try {
                Thread.sleep(2000); //give exchange 2 seconds to reflect open order in order book
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            // get open orders
            log.debug("Open orders:");
            boolean orderProcessed = false;
            int numberOfChecks = 0;
            while (!orderProcessed && numberOfChecks < 10) {
                boolean orderFound = false;
                OpenOrders openOrders = tradeService.getOpenOrders();
                for (LimitOrder openOrder : openOrders.getOpenOrders()) {
                    log.debug("openOrder = {}", openOrder);
                    if (orderId.equals(openOrder.getId())) {
                        orderFound = true;
                        break;
                    }
                }
                if (orderFound) {
                    log.debug("Waiting for order to be processed.");
                    try {
                        Thread.sleep(3000); //don't get your ip address banned
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } else {
                    orderProcessed = true;
                }
                numberOfChecks++;
            }
            if (orderProcessed) {
                return orderId;
            }
        } catch (IOException e) {
            log.error(String.format("{} exchange purchase coins failed", name), e);
        }
        return null;
    }

    @Override
    public ITask createPurchaseCoinsTask(BigDecimal amount, String cryptoCurrency, String fiatCurrencyToUse, String description) {
        cryptoCurrency = checkSupportedCrypto(cryptoCurrency);
        fiatCurrencyToUse = checkSupportedFiat(fiatCurrencyToUse);
        if (cryptoCurrency == null || fiatCurrencyToUse == null) {
            return null;
        }
        return new PurchaseCoinsTask(amount, cryptoCurrency, fiatCurrencyToUse, description);
    }

    @Override
    public String getDepositAddress(String cryptoCurrency) {
        cryptoCurrency = checkSupportedCrypto(cryptoCurrency);
        if (cryptoCurrency == null) {
            return null;
        }

        PollingAccountService accountService = exchange.getPollingAccountService();
        try {
            return accountService.requestDepositAddress(cryptoCurrency);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public String sellCoins(BigDecimal cryptoAmount, String cryptoCurrency, String fiatCurrencyToUse, String description) {
        cryptoCurrency = checkSupportedCrypto(cryptoCurrency);
        fiatCurrencyToUse = checkSupportedFiat(fiatCurrencyToUse);
        if (cryptoCurrency == null || fiatCurrencyToUse == null) {
            return null;
        }

        log.info("Calling {} exchange (sell {} {})", name, cryptoAmount, cryptoCurrency);
        PollingAccountService accountService = exchange.getPollingAccountService();
        PollingTradeService tradeService = exchange.getPollingTradeService();

        try {
            log.debug("AccountInfo as String: {}", accountService.getAccountInfo());

            CurrencyPair currencyPair = new CurrencyPair(cryptoCurrency, fiatCurrencyToUse);

            MarketOrder order = new MarketOrder(OrderType.ASK, cryptoAmount, currencyPair);
            log.debug("marketOrder = {}", order);

            String orderId = tradeService.placeMarketOrder(order);
            log.debug("orderId = {} {}", orderId, order);

            try {
                Thread.sleep(2000); //give exchange 2 seconds to reflect open order in order book
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            // get open orders
            log.debug("Open orders:");
            boolean orderProcessed = false;
            int numberOfChecks = 0;
            while (!orderProcessed && numberOfChecks < 10) {
                boolean orderFound = false;
                OpenOrders openOrders = tradeService.getOpenOrders();
                for (LimitOrder openOrder : openOrders.getOpenOrders()) {
                    log.debug("openOrder = {}", openOrder);
                    if (orderId.equals(openOrder.getId())) {
                        orderFound = true;
                        break;
                    }
                }
                if (orderFound) {
                    log.debug("Waiting for order to be processed.");
                    try {
                        Thread.sleep(3000); //don't get your ip address banned
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }else{
                    orderProcessed = true;
                }
                numberOfChecks++;
            }
            if (orderProcessed) {
                return orderId;
            }
        } catch (IOException e) {
            log.error("{} exchange sell coins failed", name, e);
        }
        return null;
    }

    @Override
    public ITask createSellCoinsTask(BigDecimal amount, String cryptoCurrency, String fiatCurrencyToUse, String description) {
        cryptoCurrency = checkSupportedCrypto(cryptoCurrency);
        fiatCurrencyToUse = checkSupportedFiat(fiatCurrencyToUse);
        if (cryptoCurrency == null || fiatCurrencyToUse == null) {
            return null;
        }
        return new SellCoinsTask(amount, cryptoCurrency, fiatCurrencyToUse, description);
    }

    @Override
    public BigDecimal getExchangeRateForBuy(String cryptoCurrency, String fiatCurrency) {
        BigDecimal result = calculateBuyPrice(cryptoCurrency, fiatCurrency, BigDecimal.TEN);
        if (result != null) {
            return result.divide(BigDecimal.TEN, 2, BigDecimal.ROUND_UP);
        }
        return null;
    }

    @Override
    public BigDecimal getExchangeRateForSell(String cryptoCurrency, String fiatCurrency) {
        BigDecimal result = calculateSellPrice(cryptoCurrency, fiatCurrency, BigDecimal.TEN);
        if (result != null) {
            return result.divide(BigDecimal.TEN, 2, BigDecimal.ROUND_DOWN);
        }
        return null;
    }

    @Override
    public BigDecimal calculateBuyPrice(String cryptoCurrency, String fiatCurrency, BigDecimal cryptoAmount) {
        cryptoCurrency = checkSupportedCrypto(cryptoCurrency);
        fiatCurrency = checkSupportedFiat(fiatCurrency);
        if (cryptoCurrency == null || fiatCurrency == null) {
            return null;
        }

        rateLimiter.acquire();
        PollingMarketDataService marketDataService = exchange.getPollingMarketDataService();
        try {
            CurrencyPair currencyPair = new CurrencyPair(cryptoCurrency, fiatCurrency);
            OrderBook orderBook = marketDataService.getOrderBook(currencyPair);
            List<LimitOrder> asks = orderBook.getAsks();
            BigDecimal targetAmount = cryptoAmount;
            BigDecimal asksTotal = BigDecimal.ZERO;
            BigDecimal tradableLimit = null;
            Collections.sort(asks, new Comparator<LimitOrder>() {
                @Override
                public int compare(LimitOrder lhs, LimitOrder rhs) {
                    return lhs.getLimitPrice().compareTo(rhs.getLimitPrice());
                }
            });

//            log.debug("Selected asks:");
            for (LimitOrder ask : asks) {
//                log.debug("ask = " + ask);
                asksTotal = asksTotal.add(ask.getTradableAmount());
                if (targetAmount.compareTo(asksTotal) <= 0) {
                    tradableLimit = ask.getLimitPrice();
                    break;
                }
            }

            if (tradableLimit != null) {
                log.debug("Called {} exchange for BUY rate: {}:{} = {}", name, cryptoCurrency, fiatCurrency, tradableLimit);
                return tradableLimit.multiply(cryptoAmount);
            }
        } catch (Throwable e) {
            log.error("{} exchange failed to calculate buy price", name, e);
        }
        return null;
    }

    @Override
    public BigDecimal calculateSellPrice(String cryptoCurrency, String fiatCurrency, BigDecimal cryptoAmount) {
        cryptoCurrency = checkSupportedCrypto(cryptoCurrency);
        fiatCurrency = checkSupportedFiat(fiatCurrency);
        if (cryptoCurrency == null || fiatCurrency == null) {
            return null;
        }

        rateLimiter.acquire();
        PollingMarketDataService marketDataService = exchange.getPollingMarketDataService();
        try {
            CurrencyPair currencyPair = new CurrencyPair(cryptoCurrency, fiatCurrency);

            OrderBook orderBook = marketDataService.getOrderBook(currencyPair);
            List<LimitOrder> bids = orderBook.getBids();

            BigDecimal targetAmount = cryptoAmount;
            BigDecimal bidsTotal = BigDecimal.ZERO;
            BigDecimal tradableLimit = null;

            Collections.sort(bids, new Comparator<LimitOrder>() {
                @Override
                public int compare(LimitOrder lhs, LimitOrder rhs) {
                    return rhs.getLimitPrice().compareTo(lhs.getLimitPrice());
                }
            });

            for (LimitOrder bid : bids) {
                bidsTotal = bidsTotal.add(bid.getTradableAmount());
                if (targetAmount.compareTo(bidsTotal) <= 0) {
                    tradableLimit = bid.getLimitPrice();
                    break;
                }
            }

            if (tradableLimit != null) {
                log.debug("Called {} exchange for SELL rate: {}:{} = {}", name, cryptoCurrency, fiatCurrency, tradableLimit);
                return tradableLimit.multiply(cryptoAmount);
            }
        } catch (Throwable e) {
            log.error("{} exchange failed to calculate sell price", name, e);
        }
        return null;

    }

    class RateCaller implements Callable<BigDecimal> {
        private final String key;

        RateCaller(String key) {
            this.key = key;
        }

        @Override
        public BigDecimal call() throws Exception {
            String[] keyParts = getKeyParts(key);
            return exchange.getPollingMarketDataService()
                    .getTicker(new CurrencyPair(keyParts[0], keyParts[1]))
                    .getLast();
        }
    }

    class BalanceCaller implements Callable<BigDecimal> {
        private final String currency;

        BalanceCaller(String currency) {
            this.currency = currency;
        }

        @Override
        public BigDecimal call() throws Exception {
            return exchange.getPollingAccountService()
                    .getAccountInfo()
                    .getWallet(currency)
                    .getBalance();
        }
    }

    class PurchaseCoinsTask implements ITask {
        private long MAXIMUM_TIME_TO_WAIT_FOR_ORDER_TO_FINISH = 5 * 60 * 60 * 1000; //5 hours

        private BigDecimal amount;
        private String cryptoCurrency;
        private String fiatCurrencyToUse;
        private String description;

        private String orderId;
        private String result;
        private boolean finished;

        PurchaseCoinsTask(BigDecimal amount, String cryptoCurrency, String fiatCurrencyToUse, String description) {
            this.amount = amount;
            this.cryptoCurrency = cryptoCurrency;
            this.fiatCurrencyToUse = fiatCurrencyToUse;
            this.description = description;
        }

        @Override
        public boolean onCreate() {
            log.debug("{} exchange purchase {} {}", name, amount, cryptoCurrency);
            PollingAccountService accountService = exchange.getPollingAccountService();
            PollingMarketDataService marketService = exchange.getPollingMarketDataService();
            PollingTradeService tradeService = exchange.getPollingTradeService();

            try {
                log.debug("AccountInfo as String: {}", accountService.getAccountInfo());

                CurrencyPair currencyPair = new CurrencyPair(cryptoCurrency, fiatCurrencyToUse);

                Ticker ticker = marketService.getTicker(currencyPair);
                LimitOrder order = new LimitOrder.Builder(OrderType.BID, currencyPair)
                        .limitPrice(ticker.getAsk())
                        .tradableAmount(amount)
                        .build();

                log.debug("limitOrder = {}", order);

                orderId = tradeService.placeLimitOrder(order);
                log.debug("orderId = {} {}", orderId, order);

                try {
                    Thread.sleep(2000); //give exchange 2 seconds to reflect open order in order book
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } catch (IOException e) {
                e.printStackTrace();
                log.error("{} exchange purchase task failed", name, e);
            }
            return (orderId != null);
        }

        @Override
        public boolean onDoStep() {
            if (orderId == null) {
                log.debug("Giving up on waiting for trade to complete. Because it did not happen");
                finished = true;
                result = "Skipped";
                return false;
            }
            PollingTradeService tradeService = exchange.getPollingTradeService();
            // get open orders
            boolean orderProcessed = false;
            long checkTillTime = System.currentTimeMillis() + MAXIMUM_TIME_TO_WAIT_FOR_ORDER_TO_FINISH;
            if (System.currentTimeMillis() > checkTillTime) {
                log.debug("Giving up on waiting for trade {} to complete", orderId);
                finished = true;
                return false;
            }

            log.debug("Open orders:");
            boolean orderFound = false;
            try {
                OpenOrders openOrders = tradeService.getOpenOrders();
                for (LimitOrder openOrder : openOrders.getOpenOrders()) {
                    log.debug("openOrder = {}", openOrder);
                    if (orderId.equals(openOrder.getId())) {
                        orderFound = true;
                        break;
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (orderFound) {
                log.debug("Waiting for order to be processed.");
            } else {
                orderProcessed = true;
            }

            if (orderProcessed) {
                result = orderId;
                finished = true;
            }

            return result != null;
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public String getResult() {
            return result;
        }

        @Override
        public boolean isFailed() {
            return finished && result == null;
        }

        @Override
        public void onFinish() {
            log.debug("Purchase task finished.");
        }

        @Override
        public long getShortestTimeForNexStepInvocation() {
            return 5 * 1000; //it doesn't make sense to run step sooner than after 5 seconds
        }
    }

    class SellCoinsTask implements ITask {
        private long MAXIMUM_TIME_TO_WAIT_FOR_ORDER_TO_FINISH = 5 * 60 * 60 * 1000; //5 hours

        private BigDecimal cryptoAmount;
        private String cryptoCurrency;
        private String fiatCurrencyToUse;
        private String description;

        private String orderId;
        private String result;
        private boolean finished;

        SellCoinsTask(BigDecimal cryptoAmount, String cryptoCurrency, String fiatCurrencyToUse, String description) {
            this.cryptoAmount = cryptoAmount;
            this.cryptoCurrency = cryptoCurrency;
            this.fiatCurrencyToUse = fiatCurrencyToUse;
            this.description = description;
        }

        @Override
        public boolean onCreate() {
            log.info("Calling {} exchange (sell {} {})", name, cryptoAmount, cryptoCurrency);
            PollingAccountService accountService = exchange.getPollingAccountService();
            PollingTradeService tradeService = exchange.getPollingTradeService();

            try {
                log.debug("AccountInfo as String: {}", accountService.getAccountInfo());

                CurrencyPair currencyPair = new CurrencyPair(cryptoCurrency, fiatCurrencyToUse);

                MarketOrder order = new MarketOrder(OrderType.ASK, cryptoAmount, currencyPair);
                log.debug("marketOrder = {}", order);

                orderId = tradeService.placeMarketOrder(order);
                log.debug("orderId = {} {}", orderId, order);

                try {
                    Thread.sleep(2000); //give exchange 2 seconds to reflect open order in order book
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } catch (IOException e) {
                e.printStackTrace();
                log.error("{} exchange sell coins task failed", name, e);
            } catch (Throwable e) {
                e.printStackTrace();
            }
            return (orderId != null);
        }

        @Override
        public boolean onDoStep() {
            if (orderId == null) {
                log.debug("Giving up on waiting for trade to complete. Because it did not happen");
                finished = true;
                result = "Skipped";
                return false;
            }
            PollingTradeService tradeService = exchange.getPollingTradeService();
            // get open orders
            boolean orderProcessed = false;
            long checkTillTime = System.currentTimeMillis() + MAXIMUM_TIME_TO_WAIT_FOR_ORDER_TO_FINISH;
            if (System.currentTimeMillis() > checkTillTime) {
                log.debug("Giving up on waiting for trade {} to complete", orderId);
                finished = true;
                return false;
            }

            log.debug("Open orders:");
            boolean orderFound = false;
            try {
                OpenOrders openOrders = tradeService.getOpenOrders();
                for (LimitOrder openOrder : openOrders.getOpenOrders()) {
                    log.debug("openOrder = {}", openOrder);
                    if (orderId.equals(openOrder.getId())) {
                        orderFound = true;
                        break;
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (orderFound) {
                log.debug("Waiting for order to be processed.");
            }else{
                orderProcessed = true;
            }

            if (orderProcessed) {
                result = orderId;
                finished = true;
            }

            return result != null;
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public String getResult() {
            return result;
        }

        @Override
        public boolean isFailed() {
            return finished && result == null;
        }

        @Override
        public void onFinish() {
            log.debug("Sell task finished.");
        }

        @Override
        public long getShortestTimeForNexStepInvocation() {
            return 5 * 1000; //it doesn't make sense to run step sooner than after 5 seconds
        }
    }

}