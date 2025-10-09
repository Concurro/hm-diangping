package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate sRedis;

    private final UserServiceImpl userServiceImpl;

    @Autowired
    public FollowServiceImpl(UserServiceImpl userServiceImpl) {
        this.userServiceImpl = userServiceImpl;
    }

    @Override
    public Result follow(Long id, Boolean isFollow) {
        if (isFollow) {
            // 添加关注
            concern(id);
        } else {
            // 取消关注
            cancelConcern(id);
        }
        return Result.ok();
    }

    private void cancelConcern(Long id) {
        Long userId = UserHolder.getUser().getId();
        boolean remove = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", id));
        if (remove) {
            // 删除Redis
            sRedis.opsForSet().remove("follow:" + userId, id.toString());
        }
    }

    private void concern(Long id) {
        Long userId = UserHolder.getUser().getId();
        Follow follow = new Follow();
        follow.setUserId(userId);
        follow.setFollowUserId(id);
        boolean save = save(follow);
        if (save) {
            // 添加到Redis
            sRedis.opsForSet().add("follow:" + userId, id.toString());
        }
    }

    @Override
    public Result isFollow(Long id) {
        return Result.ok(isFollow_w(id));
    }

    @Override
    public Result followCommons(Long id) {
        Set<String> set = sRedis.opsForSet().intersect("follow:" + id, "follow:" + UserHolder.getUser().getId());
        if (set == null || set.isEmpty()) return Result.ok(Collections.emptyList());
        List<Long> idL = set.stream().map(Long::valueOf).toList();
        return Result.ok(userServiceImpl.listByIds(idL).stream().map(user -> new UserDTO(user.getId(), user.getNickName(), user.getIcon())).toList());
    }

    private boolean isFollow_w(Long id) {
        Long userId = UserHolder.getUser().getId();
        return query().eq("user_id", userId).eq("follow_user_id", id).count() > 0;
    }
}
