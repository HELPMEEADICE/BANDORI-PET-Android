package.path = __bp_runtime_root .. "/?.lua;" .. __bp_runtime_root .. "/?/init.lua;" .. package.path
package.cpath = __bp_runtime_root .. "/?.so;" .. package.cpath
local gl = require("live2d.gl_loader")
if gl.ensureExtensions then gl.ensureExtensions() end
local raw_io_open = io.open
local renderer = nil
local width = 1
local height = 1
local groups = {}
local group_index = 1
local default_group = nil
local active_motion_kind = nil
local touch_bucket_indices = {}
local model_is_moc3 = false
local offset_x = 0
local offset_y = 0
local scale = 1
local start_motion = nil

local ACTION_GROUP_ORDER = {
    "surprised01", "shame01", "pui01", "smile01", "kandou01", "kime01",
    "nf_left01", "nnf_left01", "nf_right01", "nnf_right01", "nf01", "nnf01",
    "angry01", "sad01", "tap_body", "tap_head", "bye01",
}

local TOUCH_BUCKETS = {
    head = {
        { "surprised", "shame", "pui", "smile" },
        { "kandou", "kime", "nf" },
    },
    upper_body_left = {
        { "nf_left", "nnf_left" },
        { "shame", "surprised", "smile" },
    },
    upper_body_center = {
        { "smile", "kime", "surprised", "shame" },
        { "angry", "pui", "nf" },
    },
    upper_body_right = {
        { "nf_right", "nnf_right" },
        { "shame", "surprised", "smile" },
    },
    lower_body_left = {
        { "nf_left", "nnf_left" },
        { "surprised", "sad", "smile" },
    },
    lower_body_center = {
        { "shame", "surprised", "angry" },
        { "smile", "kime" },
    },
    lower_body_right = {
        { "nf_right", "nnf_right" },
        { "surprised", "sad", "smile" },
    },
}

local function ends_with(value, suffix)
    return value:sub(-#suffix) == suffix
end

local function normalize_path(value)
    return tostring(value or ""):gsub("\\", "/"):gsub("^%./", "")
end

local function memory_file(data)
    local pos = 1
    local file = {}
    function file:read(fmt)
        fmt = fmt or "*l"
        if fmt == "*all" or fmt == "*a" then
            local out = data:sub(pos)
            pos = #data + 1
            return out
        end
        if type(fmt) == "number" then
            if fmt <= 0 then return "" end
            local out = data:sub(pos, pos + fmt - 1)
            pos = pos + #out
            return #out > 0 and out or nil
        end
        if fmt == "*l" then
            if pos > #data then return nil end
            local next_line = data:find("\n", pos, true)
            local out
            if next_line then
                out = data:sub(pos, next_line - 1):gsub("\r$", "")
                pos = next_line + 1
            else
                out = data:sub(pos)
                pos = #data + 1
            end
            return out
        end
        return nil
    end
    function file:close() return true end
    function file:seek(whence, offset)
        offset = tonumber(offset) or 0
        if whence == nil or whence == "cur" then
            pos = pos + offset
        elseif whence == "set" then
            pos = offset + 1
        elseif whence == "end" then
            pos = #data + offset + 1
        else
            return nil, "invalid whence"
        end
        if pos < 1 then pos = 1 end
        if pos > #data + 1 then pos = #data + 1 end
        return pos - 1
    end
    return file
end

local function archive_loader(path)
    if __bp_read_resource == nil then return nil end
    return __bp_read_resource(normalize_path(path))
end

local function is_archive_path(path)
    return tostring(path or ""):sub(1, 10) == "archive://"
end

io.open = function(path, mode)
    mode = mode or "r"
    if type(path) == "string" and not mode:find("[wa+]", 1, false) then
        local data = archive_loader(path)
        if data ~= nil then return memory_file(data) end
    end
    return raw_io_open(path, mode)
end

local function resource_options(extra)
    extra = extra or {}
    extra.resource_streams = extra.resource_streams or extra.resourceStreams or { __loader = archive_loader }
    extra.resource_streams.__loader = extra.resource_streams.__loader or archive_loader
    return extra
end

local function is_idle_group(name)
    return string.find(string.lower(tostring(name or "")), "idle", 1, true) ~= nil
end

local function choose_default_group(names)
    local fallback = nil
    for _, name in ipairs(names) do
        local lower = string.lower(tostring(name))
        if lower == "idle" or lower == "idle01" then return name end
        if fallback == nil and is_idle_group(name) then fallback = name end
    end
    return fallback
end

local function append_action_group(target, name, seen)
    if name == nil or is_idle_group(name) or seen[name] then return end
    seen[name] = true
    target[#target + 1] = name
end

local function build_action_groups(names)
    local by_name = {}
    for _, name in ipairs(names) do by_name[name] = true end

    local result = {}
    local seen = {}
    for _, preferred in ipairs(ACTION_GROUP_ORDER) do
        if by_name[preferred] then append_action_group(result, preferred, seen) end
    end

    table.sort(names, function(a, b) return tostring(a) < tostring(b) end)
    for _, name in ipairs(names) do append_action_group(result, name, seen) end
    return result
end

local function clamp01(value)
    value = tonumber(value) or 0
    if value < 0 then return 0 end
    if value > 1 then return 1 end
    return value
end

local function classify_x_third(x_ratio)
    if x_ratio < 1 / 3 then return "left" end
    if x_ratio < 2 / 3 then return "center" end
    return "right"
end

local function motion_base_and_side(name)
    local text = string.lower(tostring(name or "")):gsub("\\", "/")
    local token = text:match("([^/]+)$") or text
    token = token:gsub("%.motion3?%.json$", "")
    token = token:gsub("^mtn_", "")

    local side = nil
    local suffix = token:match("_([lcr])$")
    if suffix ~= nil then
        if suffix == "l" then side = "left" end
        if suffix == "c" then side = "center" end
        if suffix == "r" then side = "right" end
        token = token:sub(1, -3)
    end

    token = token:gsub("%d+$", "")
    return token, side
end

local function split_directional_tag(tag)
    local text = string.lower(tostring(tag or ""))
    local base, side = text:match("^(.-)_(left)$")
    if base ~= nil then return base, side end
    base, side = text:match("^(.-)_(center)$")
    if base ~= nil then return base, side end
    base, side = text:match("^(.-)_(right)$")
    if base ~= nil then return base, side end
    return text, nil
end

local function motion_matches_tag(name, tag)
    local base, side = motion_base_and_side(name)
    local tag_base, tag_side = split_directional_tag(tag)
    if tag_side ~= nil then
        return base == tag_base .. "_" .. tag_side or (base == tag_base and side == tag_side)
    end
    return base == tag_base
end

local function candidates_for_tags(tags)
    local result = {}
    local seen = {}
    for _, tag in ipairs(tags or {}) do
        for _, group in ipairs(groups) do
            if not seen[group] and motion_matches_tag(group, tag) then
                seen[group] = true
                result[#result + 1] = group
            end
        end
    end
    return result
end

local function any_hit_is_head_or_face(hit_parts)
    if type(hit_parts) ~= "table" then return false end
    for _, part in pairs(hit_parts) do
        local name = string.lower(tostring(part or ""))
        if name:find("head", 1, true) or name:find("face", 1, true) then
            return true
        end
    end
    return false
end

local function classify_touch_region(x_ratio, y_ratio)
    x_ratio = clamp01(x_ratio)
    y_ratio = clamp01(y_ratio)

    local hit_parts = nil
    if renderer and renderer.hit_test then
        local ok, result = pcall(function() return renderer:hit_test(x_ratio * width, y_ratio * height) end)
        if ok then hit_parts = result end
    end
    if any_hit_is_head_or_face(hit_parts) or y_ratio < 0.38 then
        return "head"
    end

    local column = classify_x_third(x_ratio)
    if y_ratio < 0.64 then
        return "upper_body_" .. column
    end
    return "lower_body_" .. column
end

local function try_start_from_candidates(candidates, key)
    if #candidates == 0 then return false end
    local start_index = touch_bucket_indices[key] or 1
    for offset = 0, #candidates - 1 do
        local index = ((start_index + offset - 1) % #candidates) + 1
        local group = candidates[index]
        local ok, started = pcall(function() return start_motion(group, false, 3) end)
        if ok and started then
            touch_bucket_indices[key] = index % #candidates + 1
            active_motion_kind = "action"
            return true
        end
    end
    return false
end

local function collect_moc3_groups()
    groups = {}
    default_group = nil
    local motions = {}
    if renderer.model_info then
        motions = renderer:model_info().motions or {}
    elseif renderer.get_model_data then
        local refs = renderer:get_model_data() and renderer:get_model_data().file_references
        motions = (refs and refs.motions) or {}
    end
    local names = {}
    for name, _ in pairs(motions) do
        names[#names + 1] = name
    end
    default_group = choose_default_group(names)
    groups = build_action_groups(names)
end

local function collect_moc_groups()
    groups = {}
    default_group = nil
    local names = {}
    local model = renderer.get_model and renderer:get_model() or nil
    local model_setting = model and model.modelSetting or nil
    if model_setting and model_setting.getMotionNames then
        names = model_setting:getMotionNames() or {}
    end
    default_group = choose_default_group(names)
    groups = build_action_groups(names)
    if #groups == 0 then
        groups = {"tap_body", "tap_head", "angry01", "bye01", "kime01", "smile01", "nf01", "nnf01"}
    end
end

local function is_motion_finished()
    if not renderer then return true end
    if renderer.is_motion_finished then return renderer:is_motion_finished() end
    local model = renderer.get_model and renderer:get_model() or nil
    local manager = model and model.mainMotionManager or nil
    return manager == nil or manager:isFinished()
end

function start_motion(group, loop, priority)
    if not renderer or group == nil then return false end
    if model_is_moc3 then
        renderer:start_motion(group, 0, priority, loop)
        return true
    end

    local model = renderer.get_model and renderer:get_model() or nil
    if model and model.modelSetting and model.modelSetting.getMotionFile then
        local file = model.modelSetting:getMotionFile(group, 0)
        if file == nil or file == "" then return false end
        model:StartMotion(group, 0, priority or 3)
        local motion = model.motions and model.motions[tostring(group) .. "#0"] or nil
        if motion ~= nil then motion.loop = loop == true end
        return true
    end

    renderer:start_motion(group, 0, priority)
    return true
end

local function start_default_motion()
    if default_group == nil then
        if renderer and renderer.clear_motions then renderer:clear_motions() end
        active_motion_kind = nil
        return
    end
    if start_motion(default_group, true, 1) then
        active_motion_kind = "default"
    end
end

function __bp_load(path, w, h)
    width = w
    height = h
    active_motion_kind = nil
    touch_bucket_indices = {}
    group_index = 1
    if ends_with(path, ".model3.json") then
        model_is_moc3 = true
        local embed = require("live2d_moc3_pet_embed")
        renderer = embed.new(width, height)
        renderer:load_model(path, width, height, is_archive_path(path) and resource_options() or nil)
        collect_moc3_groups()
    else
        model_is_moc3 = false
        local embed = require("live2d_embed")
        renderer = embed.new(width, height)
        local opts = { center = false }
        if is_archive_path(path) then opts = resource_options(opts) end
        renderer:load_model(path, width, height, opts)
        collect_moc_groups()
    end
    if renderer.set_offset then renderer:set_offset(offset_x, offset_y) end
    if renderer.set_scale then renderer:set_scale(scale) end
    start_default_motion()
    return true
end

function __bp_resize(w, h)
    width = w
    height = h
    if renderer and renderer.resize then renderer:resize(width, height) end
end

function __bp_touch(x_ratio, y_ratio)
    if not renderer or #groups == 0 then return end
    local region = classify_touch_region(x_ratio, y_ratio)
    local buckets = TOUCH_BUCKETS[region] or TOUCH_BUCKETS.head
    for bucket_index, tags in ipairs(buckets) do
        local candidates = candidates_for_tags(tags)
        if try_start_from_candidates(candidates, region .. ":" .. tostring(bucket_index)) then
            return
        end
    end

    for _ = 1, #groups do
        local group = groups[group_index]
        group_index = group_index % #groups + 1
        local ok, started = pcall(function() return start_motion(group, false, 3) end)
        if ok and started then
            active_motion_kind = "action"
            return
        end
    end
end

function __bp_action(tag)
    if not renderer then return false end
    tag = string.lower(tostring(tag or "")):gsub("^%[", ""):gsub("%]$", "")
    if tag == "" then return false end
    if ends_with(tag, ".exp") and renderer.set_expression then
        local ok = pcall(function() renderer:set_expression(tag) end)
        if ok then return true end
        local name = tag:sub(1, -5)
        return pcall(function() renderer:set_expression(name) end)
    end
    local candidates = candidates_for_tags({ tag })
    if try_start_from_candidates(candidates, "llm:" .. tag) then return true end
    return false
end

function __bp_look_at(x_ratio, y_ratio)
    if not renderer or renderer.drag == nil then return end
    x_ratio = clamp01(x_ratio)
    y_ratio = clamp01(y_ratio)
    renderer:drag(x_ratio * width, y_ratio * height)
end

function __bp_transform(x, y, s)
    offset_x = tonumber(x) or 0
    offset_y = tonumber(y) or 0
    scale = tonumber(s) or 1
    if not renderer then return end
    if renderer.set_offset then renderer:set_offset(offset_x, offset_y) end
    if renderer.set_scale then renderer:set_scale(scale) end
end

function __bp_clear()
    gl.glViewport(0, 0, width, height)
    gl.glClearColor(0.0, 0.0, 0.0, 0.0)
    gl.glClear(0x4000)
end

function __bp_draw(time_msec, mouth_open, mouth_form)
    if not renderer then return end
    gl.glViewport(0, 0, width, height)
    local parameters
    if model_is_moc3 then
        parameters = {
            { id = "ParamMouthOpenY", value = tonumber(mouth_open) or 0, weight = 1 },
            { id = "ParamMouthForm", value = tonumber(mouth_form) or 0, weight = 1 },
        }
    else
        parameters = {
            { id = "PARAM_MOUTH_OPEN_Y", value = tonumber(mouth_open) or 0, weight = 1 },
            { id = "PARAM_MOUTH_FORM", value = tonumber(mouth_form) or 0, weight = 1 },
        }
    end
    renderer:draw({ clear = false, time_msec = time_msec, parameters = parameters })
    if active_motion_kind == "action" and is_motion_finished() then
        start_default_motion()
    end
end
