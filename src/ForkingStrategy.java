import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ForkingStrategy {

    //в рельной жизни это параметры стратегии
    private int maxConcurrencyLevel = 3;
    private int[] delays = new int[] {1, 1, 2};

    //в реальной жизни это как-будто бы случайные значение:
    private int[] latencies = new int[] {4,3,1};

    /**
     * @return CompletableFuture which has HttpResult as a result.
     * Метод Cancel() на этой future остановит отправку новых http-запросов и вызовет cancel() на future'ах,
     * которые httpClient вернул как результат уже отправленных запросов.
     * httpClient отвечает за корректную обработку cancel() на future, которые он возвращает.
     */
    public CompletableFuture<HttpResult> sendAsync(HttpClient httpClient) {
        RequestContext context = RequestContextFactory.create(httpClient, maxConcurrencyLevel);
        sendNextAsyncRequest(context);
        return context.getResultFuture();
    }

    private void sendNextAsyncRequest(RequestContext context) {
        Log.println("Sending the request #" + context.getCurrentConcurrencyLevel());
        CompletableFuture<HttpResult> resultFuture = context.getHttpClient().sendAsync(latencies[context.getCurrentConcurrencyLevel()]);
        context.getActiveFutures()[context.getCurrentConcurrencyLevel()] = resultFuture;

        CompletableFuture delayFuture = AsyncDelay.delay(delays[context.getCurrentConcurrencyLevel()], TimeUnit.SECONDS);
        context.getActiveFutures()[context.getCurrentConcurrencyLevel()+1] = delayFuture;

        context.incrementCurrentConcurrencyLevel();
        CompletableFuture.anyOf(context.getActiveFutures()).thenAccept(o -> onResponseOrTimeout(o, context));
    }

    private void onResponseOrTimeout(Object futureResult, RequestContext context) {

        if (context.IsRequestCancelled()) {
            for (int i = 0; i < context.getCurrentConcurrencyLevel() + 1; i++) {
                context.getActiveFutures()[i].cancel(false);
            }
            return;
        }

        if (futureResult instanceof HttpResult) {
            for (int i = 0; i < context.getCurrentConcurrencyLevel() + 1; i++) {
                context.getActiveFutures()[i].cancel(false); //todo: is it necessary to cancel() a delay ?
            }
            Log.println("Completing future");
            context.getResultFuture().complete((HttpResult) futureResult);
        }

        //за отведенное время не дождались ответа
        if (context.getCurrentConcurrencyLevel() < maxConcurrencyLevel) {
            sendNextAsyncRequest(context);
        }
        else {
            //Больше нет реплик, пора вернуть timeout (не придерайтесь, это прототип)
            context.getResultFuture().complete(new HttpResult(408));
        }
    }
}
