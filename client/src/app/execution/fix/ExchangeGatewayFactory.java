package app.execution.fix;

public final class ExchangeGatewayFactory {
    private ExchangeGatewayFactory() {
    }

    public static ExecutionGateway create(ExchangeAccountSettings account) {
        ExchangeAccountSettings settings = account == null ? new ExchangeAccountSettings() : account;
        if (settings.mode == ExchangeAccountSettings.Mode.PAPER) {
            return new PaperFixGateway("PAPER-" + settings.exchange.label);
        }
        if (settings.exchange == ExchangeAccountSettings.Exchange.BINGX
                && settings.mode == ExchangeAccountSettings.Mode.TESTNET) {
            throw new IllegalStateException("BingX testnet не поддержан этим адаптером; выбери Paper или Live");
        }
        boolean testnet = settings.mode == ExchangeAccountSettings.Mode.TESTNET;
        return switch (settings.exchange) {
            case BINANCE -> new BinanceFuturesRestGateway(settings.apiKey, settings.apiSecret, testnet);
            case BYBIT -> new BybitFuturesRestGateway(settings.apiKey, settings.apiSecret, testnet);
            case BINGX -> new BingXFuturesRestGateway(settings.apiKey, settings.apiSecret, settings.passphrase, testnet);
        };
    }
}
