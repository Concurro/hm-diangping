package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) return Result.fail("手机号格式错误");
        // 2.生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 3.保存验证码到Redis中2分钟
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 4.发送验证码
        log.debug("发送验证码成功，验证码：{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号和验证码
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 2.校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        // 3.验证码不存在
        if (cacheCode == null) return Result.fail("请发送验证码!");
        if (!cacheCode.equals(code)) return Result.fail("验证码错误!");
        // 4.查询用户
        User user = query().eq("phone", phone).one();
        if (user == null) user = createUserWithPhone(phone);
        // 5.保存用户信息到session（转换为UserDTO）
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        String uuid = UUID.randomUUID().toString(true);
        // 6.保存用户信息到Redis
        // 创建hash对象
        Map<String, Object> map = BeanUtil.
                beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                        .setIgnoreNullValue(true) // 忽略null值
                        .setFieldValueEditor((fieldName, value) -> value == null ? "" : value.toString()) // 值转为字符串，处理null值
                );


        stringRedisTemplate.opsForHash().putAll("login:token:" + uuid, map);
        // 设置token有效期
        stringRedisTemplate.expire("login:token:" + uuid, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(uuid);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("Sbu_" + RandomUtil.randomString(5));
        save(user);
        return user;
    }
}
