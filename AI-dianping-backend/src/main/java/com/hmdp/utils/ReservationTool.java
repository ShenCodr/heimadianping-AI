package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Reservation;
import com.hmdp.service.IReservationService;
import com.hmdp.service.IUserService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

import java.time.LocalDateTime;

@Component
public class ReservationTool {

    private final IReservationService reservationService;

    private final IUserService userService;

    public ReservationTool(IReservationService reservationService, IUserService userService) {
        this.reservationService = reservationService;
        this.userService = userService;
    }

    @Tool("预约店铺服务，需要提供店铺ID和预约时间")
    public String addReservation(
            @P("商户id") Long shopId,
            @P("预约时间, 格式为 yyyy-MM-dd HH:mm:ss") String reservationTime
    ) {
        // 手动解析字符串为 LocalDateTime
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime dateTime;
        try {
            dateTime = LocalDateTime.parse(reservationTime, formatter);
        } catch (DateTimeParseException e) {
            return "预约时间格式不正确，请使用 yyyy-MM-dd HH:mm:ss 格式。";
        }
        // 1. 直接使用当前登录的用户信息
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return "无法获取当前用户信息，请先登录。";
        }
        Long userId = user.getId();
        String userPhone = userService.getById(userId).getPhone();

        // 2. 创建并保存预约
        Reservation reservation = new Reservation();
        reservation.setUserId(userId);
        reservation.setUserPhone(userPhone);
        reservation.setShopId(shopId);
        reservation.setReservationTime(dateTime); // 使用解析后的 dateTime
        reservation.setStatus(0);
        reservation.setCreateTime(LocalDateTime.now());
        reservationService.save(reservation);

        return "预约成功！";
    }

    @Tool("查询我的预约信息")
    public List<Reservation> findMyReservation( ) {
        // 1. 直接当前登录的用户ID
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 或者可以抛出异常，让AI知道出错了
            return null;
        }
        // 2. 根据用户ID查询预约
        // 注意：getById 是根据主键查询，这里需要一个根据 userId 查询的方法
        return reservationService.query().eq("user_id", user.getId()).list();
    }
}
