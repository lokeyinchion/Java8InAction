package lambdasinaction.chap11.v1;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lambdasinaction.chap11.ExchangeService;
import lambdasinaction.chap11.ExchangeService.Money;

public class BestPriceFinder {

    private final List<Shop> shops = Arrays.asList(new Shop("BestPrice"),
                                                   new Shop("LetsSaveBig"),
                                                   new Shop("MyFavoriteShop"),
                                                   new Shop("BuyItAll")/*,
                                                   new Shop("ShopEasy")*/);

    private final Executor executor = Executors.newFixedThreadPool(Math.min(shops.size(),100), r -> {
        Thread t = new Thread(r);
        //守护线程,当程序退出时被回收
        t.setDaemon(true);
        return t;
    });

    /**
     * @ getPrice()模拟耗时1s,流式顺序调用耗时4s左右,没有优化效果
     * @param product
     * @return
     */
    public List<String> findPricesSequential(String product) {
        return shops.stream()
                .map(shop -> shop.getName() + " price is " + shop.getPrice(product))
                .collect(Collectors.toList());
    }

    /**
     * @ 并行调用,整体耗时1s左右(cpu数目为4),相比顺序调用有很大提升
     * @param product
     * @return 同时能并行的线程受限于机器cpu数目
     */
    public List<String> findPricesParallel(String product) {
        return shops.parallelStream()
                .map(shop -> shop.getName() + " price is " + shop.getPrice(product))
                .collect(Collectors.toList());
    }

    /**
     * @desc 封装流式顺序调用为CompletableFuture式异步形式
     * 同时指定线程池方式(可以创建更过线程数,充分发挥cpu性能,在线程数需超过cpu数的场景下,使其性能优于并行流)
     * @param product
     * @return 如果不指定executor耗时将超过2s,效果比并行流方式差
     *         指定executor后耗时维持在1.2s,直到商店数达到计算阈值
     */
    public List<String> findPricesFuture(String product) {
        List<CompletableFuture<String>> priceFutures =
                shops.stream()
                .map(shop -> CompletableFuture.supplyAsync(() -> shop.getName() + " price is "
                        + shop.getPrice(product), executor))
                .collect(Collectors.toList());

        List<String> prices = priceFutures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
        return prices;
    }

    /**
     * @desc 耗时3s
     * @param product
     * @return
     */
    public List<String> findPricesInUSD(String product) {
        List<CompletableFuture<Double>> priceFutures = new ArrayList<>();
        for (Shop shop : shops) {
            // Start of Listing 10.20.
            // Only the type of futurePriceInUSD has been changed to
            // CompletableFuture so that it is compatible with the
            // CompletableFuture::join operation below.
            CompletableFuture<Double> futurePriceInUSD = 
                CompletableFuture.supplyAsync(() -> shop.getPrice(product))
                .thenCombine(
                    CompletableFuture.supplyAsync(
                        () ->  ExchangeService.getRate(Money.EUR, Money.USD)),
                    (price, rate) -> price * rate
                );
            priceFutures.add(futurePriceInUSD);
        }
        // Drawback: The shop is not accessible anymore outside the loop,
        // so the getName() call below has been commented out.
        List<String> prices = priceFutures
                .stream()
                .map(CompletableFuture::join)
                .map(price -> /*shop.getName() +*/ " price is " + price)
                .collect(Collectors.toList());
        return prices;
    }

    public List<String> findPricesInUSDJava7(String product) {
        ExecutorService executor = Executors.newCachedThreadPool();
        List<Future<Double>> priceFutures = new ArrayList<>();
        for (Shop shop : shops) {
            final Future<Double> futureRate = executor.submit(() -> ExchangeService.getRate(Money.EUR, Money.USD));
            Future<Double> futurePriceInUSD = executor.submit(() -> {
                try {
                    double priceInEUR = shop.getPrice(product);
                    return priceInEUR * futureRate.get();
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            });
            priceFutures.add(futurePriceInUSD);
        }
        List<String> prices = new ArrayList<>();
        for (Future<Double> priceFuture : priceFutures) {
            try {
                prices.add(/*shop.getName() +*/ " price is " + priceFuture.get());
            }
            catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }
        return prices;
    }

    /**
     * @desc 耗时3s
     * @param product
     * @return
     */
    public List<String> findPricesInUSD2(String product) {
        List<CompletableFuture<String>> priceFutures = new ArrayList<>();
        for (Shop shop : shops) {
            // Here, an extra operation has been added so that the shop name
            // is retrieved within the loop. As a result, we now deal with
            // CompletableFuture<String> instances.
            CompletableFuture<String> futurePriceInUSD = 
                CompletableFuture.supplyAsync(() -> shop.getPrice(product))
                .thenCombine(
                    CompletableFuture.supplyAsync(
                        () -> ExchangeService.getRate(Money.EUR, Money.USD)),
                    (price, rate) -> price * rate
                ).thenApply(price -> shop.getName() + " price is " + price);
            priceFutures.add(futurePriceInUSD);
        }
        List<String> prices = priceFutures
                .stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
        return prices;
    }

    /**
     * @desc 耗时3s
     * @param product
     * @return
     */
    public List<String> findPricesInUSD3(String product) {
        // Here, the for loop has been replaced by a mapping function...
        Stream<CompletableFuture<String>> priceFuturesStream = shops
            .stream()
            .map(shop -> CompletableFuture
                .supplyAsync(() -> shop.getPrice(product))
                .thenCombine(
                    CompletableFuture.supplyAsync(() -> ExchangeService.getRate(Money.EUR, Money.USD)),
                    (price, rate) -> price * rate)
                .thenApply(price -> shop.getName() + " price is " + price));
        // However, we should gather the CompletableFutures into a List so that the asynchronous
        // operations are triggered before being "joined."
        List<CompletableFuture<String>> priceFutures = priceFuturesStream.collect(Collectors.toList());
        List<String> prices = priceFutures
            .stream()
            .map(CompletableFuture::join)
            .collect(Collectors.toList());
        return prices;
    }

}
