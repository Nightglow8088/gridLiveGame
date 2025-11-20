package com.example.pupupudemo.service;

import lombok.Data;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
@Data
public class LiveDataService {

    private final RestTemplate restTemplate = new RestTemplate();

    // 默认值
    private double currentBtcPrice = 50000.0;

    // 游戏规则参数
    private int hpCostPerTurn = 1;

    // 每 60 秒更新一次
    @Scheduled(fixedRate = 60000)
    public void fetchRealWorldData() {
        try {
            // 👇【修改处】使用 Binance US (美国版 API)
            // 如果你在美国，必须用 binance.us，且交易对通常是 BTCUSD
            String cryptoUrl = "https://api.binance.us/api/v3/ticker/price?symbol=BTCUSD";

            // 备用方案：如果 Binance US 也不行，可以用 Coinbase (绝对稳)
            // String cryptoUrl = "https://api.coinbase.com/v2/prices/BTC-USD/spot";
            // 注意：Coinbase 返回的 JSON 结构不一样，需要改解析逻辑，所以先试 Binance US

            Map cryptoResp = restTemplate.getForObject(cryptoUrl, Map.class);

            if (cryptoResp != null && cryptoResp.containsKey("price")) {
                this.currentBtcPrice = Double.parseDouble(cryptoResp.get("price").toString());

                // 2. 更新游戏规则
                updateGameRules();

                System.out.println(">>> 🌍 真实世界同步 (Binance US) | BTC价格: $" + currentBtcPrice + " | 生存消耗: " + hpCostPerTurn + " HP");
            }

        } catch (Exception e) {
            System.err.println("获取数据失败 (" + e.getClass().getSimpleName() + "): " + e.getMessage());
            // 如果一直失败，会自动保持上一次的 currentBtcPrice，不会崩溃
        }
    }

    private void updateGameRules() {
        // 规则：币价 > 90000 (牛市) -> 扣 1 血
        //       币价 <= 90000 (熊市) -> 扣 2 血
        if (currentBtcPrice > 90000.0) {
            this.hpCostPerTurn = 1;
        } else {
            this.hpCostPerTurn = 2;
        }
    }
}