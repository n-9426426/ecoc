package com.ruoyi.vehicle;

import com.ruoyi.common.security.annotation.EnableCustomConfig;
import com.ruoyi.common.security.annotation.EnableRyFeignClients;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 车辆信息模块
 * 
 * @author 张奥
 */
@EnableCustomConfig
@EnableRyFeignClients
@SpringBootApplication
@EnableScheduling
public class RuoYiVehicleApplication
{
    public static void main(String[] args)
    {
        SpringApplication.run(RuoYiVehicleApplication.class, args);
        System.out.println("(♥◠‿◠)ﾉﾞ  车辆信息模块启动成功   ლ(´ڡ`ლ)ﾞ  \n" +
                " .-------.       ____     __        \n" +
                " |  _ _   \\      \\   \\   /  /    \n" +
                " | ( ' )  |       \\  _. /  '       \n" +
                " |(_ o _) /        _( )_ .'         \n" +
                " | (_,_).' __  ___(_ o _)'          \n" +
                " |  |\\ \\  |  ||   |(_,_)'         \n" +
                " |  | \\ `'   /|   `-'  /           \n" +
                " |  |  \\    /  \\      /           \n" +
                " ''-'   `'-'    `-..-'              ");
    }
}
