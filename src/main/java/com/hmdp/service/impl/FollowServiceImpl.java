package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    private final UserServiceImpl userServiceImpl;
    private final JdbcTemplate jdbcTemplate;

    public FollowServiceImpl(UserServiceImpl userServiceImpl, JdbcTemplate jdbcTemplate) {
        this.userServiceImpl = userServiceImpl;
        this.jdbcTemplate = jdbcTemplate;
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
        remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", id));
    }

    private void concern(Long id) {
        Long userId = UserHolder.getUser().getId();
        Follow follow = new Follow();
        follow.setUserId(userId);
        follow.setFollowUserId(id);
        save(follow);
    }

    @Override
    public Result isFollow(Long id) {
        return Result.ok(isFollow_w(id));
    }

    @Override
    public Result followCommons(Long targetUserId) {
        Long currentUserId = UserHolder.getUser().getId();

        String sql = "SELECT a.follow_user_id " +
                "FROM tb_follow a " +
                "JOIN tb_follow b ON a.follow_user_id = b.follow_user_id " +
                "WHERE a.user_id = ? AND b.user_id = ?";

        // 使用可变参数形式
        List<Long> commonIds = jdbcTemplate.queryForList(
                sql,
                Long.class,  // 结果类型
                currentUserId, targetUserId  // 参数
        );
        if (commonIds.isEmpty()) return Result.ok(Collections.emptyList());
        List<UserDTO> commonUsers = userServiceImpl.query().in("id", commonIds).list().stream()
                .map(user -> new UserDTO(user.getId(), user.getNickName(), user.getIcon())).toList();

        return Result.ok(commonUsers);
    }

    private boolean isFollow_w(Long id) {
        Long userId = UserHolder.getUser().getId();
        return query().eq("user_id", userId).eq("follow_user_id", id).count() > 0;
    }
}
